package com.property.ai.domain.ticket;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.property.ai.bff.dto.TicketCategory;
import com.property.ai.bff.dto.TicketUrgency;

/**
 * 派单规则服务（P2-017）：技能映射（{@code TicketCategory × urgency → skillTag}）、
 * 楼栋/区域 → 班组（内置 mock 排班表）、ETA（今日上门时段 + 队列长度 → 预计上门时间人话）。
 *
 * <p>⚠️ <b>demo 数据</b>：{@link #SCHEDULE} 是写死在 Java 内的 mock 排班表（4 个班组以上），
 * 仅用于 P2 演示与单测；<b>不是真实排班/工单派工系统数据</b>，也不来自 ERP 主数据。
 * 生产接入真实排班（P5 多项目）时替换 {@code ScheduleSource} 即可，规则/ETA 算法不变。
 *
 * <p>技能映射表（category × urgency → skillTag）：
 * <pre>
 * | category        | normal / urgent | emergency        |
 * |-----------------|-----------------|------------------|
 * | plumbing        | 水暖            | 水暖-应急抢修    |
 * | electrical      | 强电            | 强电-应急抢修    |
 * | elevator        | 电梯            | 电梯-困人应急    |
 * | door_window     | 门窗            | 门窗-应急抢修    |
 * | public_facility | 公共设施        | 公共设施-应急抢修 |
 * | other           | 综合维修        | 综合维修-应急抢修 |
 * </pre>
 *
 * <p>班组匹配：先按 skillTag 的「技能组」（去掉 {@code -应急抢修} 等后缀）过滤班组，
 * 再按楼栋（{@code roomCode} 首段 → {@code N栋}）优先；紧急工单优先 24h 应急班组；
 * 同条件下按「当前队列长度升序 + 班组 ID」稳定选择。楼栋无对应班组 → 跨楼栋支援（技能匹配的最近班组）。
 *
 * <p>ETA：{@code 到达 = max(现在, 今日时段开始) + 30 分钟在途 + 队列长度×45 分钟（紧急单跳过排队）}，
 * 向上取整到 5 分钟；超出今日时段 → 次日首段。输出人话如「今天 16:30」「明天 09:00」。
 */
@Service
public class DispatchService {

    /** 排班班组（demo 数据；技能组/负责楼栋/当前队列/可用时段） */
    public record Team(String teamId, String teamName, List<String> skills, List<String> buildings,
            int queueLength, String availableFrom, String availableTo, boolean emergency24h) {
    }

    /** 派单结果（映射为契约 DispatchPayload + ETA 明细） */
    public record Assignment(String teamId, String teamName, String skillTag, String assignee, String etaText,
            String etaIso, String building, int queueLength) {
    }

    /** ETA 参数：在途 30 分钟、每单排队 45 分钟、结果 5 分钟取整 */
    static final int TRAVEL_MINUTES = 30;
    static final int QUEUE_SLOT_MINUTES = 45;
    static final int ROUND_MINUTES = 5;

    /**
     * 内置 mock 排班表（⚠️ demo：P2 演示用，非真实排班系统数据）。
     * 5 个班组：水电一班/二班（水暖）、强电一班（强电）、综合维修班（门窗/公共设施/综合维修）、电梯专修班（电梯）。
     */
    public static final List<Team> SCHEDULE = List.of(
            new Team("TEAM-PLUMB-01", "水电一班", List.of("水暖"), List.of("1栋", "2栋"), 2,
                    "08:30", "18:00", false),
            new Team("TEAM-PLUMB-02", "水电二班", List.of("水暖"), List.of("3栋", "4栋"), 3,
                    "08:30", "18:00", true),
            new Team("TEAM-ELEC-01", "强电一班", List.of("强电"), List.of("1栋", "3栋", "4栋"), 1,
                    "09:00", "18:00", true),
            new Team("TEAM-GEN-01", "综合维修班", List.of("门窗", "公共设施", "综合维修"),
                    List.of("1栋", "2栋", "3栋", "4栋"), 2, "08:00", "17:30", false),
            new Team("TEAM-ELEV-01", "电梯专修班", List.of("电梯"), List.of("1栋", "2栋", "3栋", "4栋"), 1,
                    "00:00", "23:59", true));

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private Clock clock = Clock.system(TicketTimes.BUSINESS_ZONE);

    /** 测试注入时钟（生产不调用） */
    void setClockForTest(Clock clock) {
        this.clock = clock == null ? Clock.system(TicketTimes.BUSINESS_ZONE) : clock;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** mock 排班表快照（验收/报告用；demo 数据） */
    public List<Team> schedule() {
        return SCHEDULE;
    }

    /** 按班组名/班组 ID 精确查找（找不到返回空，不猜测） */
    public Optional<Team> findTeam(String teamNameOrId) {
        if (teamNameOrId == null || teamNameOrId.isBlank()) {
            return Optional.empty();
        }
        String key = teamNameOrId.trim();
        return SCHEDULE.stream()
                .filter(team -> team.teamName().equals(key) || team.teamId().equalsIgnoreCase(key))
                .findFirst();
    }

    /** 技能映射：category × urgency → skillTag（契约 DispatchPayload.skillTag） */
    public static String skillFor(TicketCategory category, TicketUrgency urgency) {
        String base = switch (category == null ? TicketCategory.OTHER : category) {
            case PLUMBING -> "水暖";
            case ELECTRICAL -> "强电";
            case ELEVATOR -> "电梯";
            case DOOR_WINDOW -> "门窗";
            case PUBLIC_FACILITY -> "公共设施";
            case OTHER -> "综合维修";
        };
        if (urgency == TicketUrgency.EMERGENCY) {
            return TicketCategory.ELEVATOR == category ? base + "-困人应急" : base + "-应急抢修";
        }
        return base;
    }

    /** 技能组（去掉「-应急抢修 / -困人应急」后缀，用于班组匹配） */
    public static String skillGroupOf(String skillTag) {
        if (skillTag == null) {
            return "";
        }
        int index = skillTag.indexOf('-');
        return index < 0 ? skillTag.trim() : skillTag.substring(0, index).trim();
    }

    /** 房号 → 楼栋（{@code 3-2-1802 → 3栋}）；无法解析返回空串（跨楼栋支援） */
    public static String buildingOf(String roomCode) {
        if (roomCode == null || roomCode.isBlank()) {
            return "";
        }
        String trimmed = roomCode.trim();
        int index = trimmed.indexOf('-');
        String first = index < 0 ? trimmed : trimmed.substring(0, index);
        return first.isBlank() ? "" : first + "栋";
    }

    // ------------------------------------------------------------------
    // 派单
    // ------------------------------------------------------------------

    /**
     * 自动派单（{@code action=dispatch} 未带 teamName/teamId 时）：按技能 + 楼栋 + 队列选班组并算 ETA。
     */
    public Assignment autoAssign(TicketCategory category, TicketUrgency urgency, String roomCode) {
        String skillTag = skillFor(category, urgency);
        String skillGroup = skillGroupOf(skillTag);
        String building = buildingOf(roomCode);
        boolean emergency = urgency == TicketUrgency.EMERGENCY;

        List<Team> candidates = SCHEDULE.stream().filter(team -> team.skills().contains(skillGroup)).toList();
        if (candidates.isEmpty()) {
            candidates = SCHEDULE;
        }
        List<Team> byBuilding = building.isBlank() ? List.of()
                : candidates.stream().filter(team -> team.buildings().contains(building)).toList();
        List<Team> pool = byBuilding.isEmpty() ? candidates : byBuilding;
        List<Team> emergencyPool = emergency ? pool.stream().filter(Team::emergency24h).toList() : List.of();
        List<Team> finalPool = emergencyPool.isEmpty() ? pool : emergencyPool;

        Team picked = finalPool.stream()
                .min(Comparator.comparingInt(Team::queueLength).thenComparing(Team::teamId))
                .orElse(SCHEDULE.get(SCHEDULE.size() - 1));
        return assignment(picked, skillTag, "", emergency, building);
    }

    /**
     * 人工指定班组派单：校验班组存在（非法 → 400 E-1003），ETA 可沿用 W5 人话文案或按排班表计算。
     */
    public Assignment assignTo(String teamNameOrId, TicketCategory category, TicketUrgency urgency, String roomCode,
            String etaOverride, String assignee, String skillTagOverride) {
        Team team = findTeam(teamNameOrId)
                .orElseThrow(() -> DomainException.unknownScheduleTeam(teamNameOrId));
        String skillTag = skillTagOverride == null || skillTagOverride.isBlank()
                ? skillFor(category, urgency)
                : skillTagOverride.trim();
        return assignment(team, skillTag, assignee == null ? "" : assignee.trim(),
                urgency == TicketUrgency.EMERGENCY, buildingOf(roomCode), etaOverride);
    }

    private Assignment assignment(Team team, String skillTag, String assignee, boolean emergency, String building) {
        return assignment(team, skillTag, assignee, emergency, building, null);
    }

    private Assignment assignment(Team team, String skillTag, String assignee, boolean emergency, String building,
            String etaOverride) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), TicketTimes.BUSINESS_ZONE);
        LocalDateTime arrival = etaOverride == null || etaOverride.isBlank()
                ? computeArrival(team, emergency, now)
                : null;
        String etaText = arrival == null ? etaOverride.trim() : humanEta(arrival, now);
        String etaIso = arrival == null ? null : etaIso(arrival);
        return new Assignment(team.teamId(), team.teamName(), skillTag, assignee, etaText, etaIso, building,
                team.queueLength());
    }

    /** ETA 计算：今日时段 + 队列长度 → 预计到达（已按 5 分钟取整；超时段转次日首段） */
    LocalDateTime computeArrival(Team team, boolean emergency, LocalDateTime now) {
        LocalTime from = LocalTime.parse(team.availableFrom());
        LocalTime to = LocalTime.parse(team.availableTo());
        LocalDateTime start = now;
        if (start.toLocalTime().isBefore(from)) {
            start = now.toLocalDate().atTime(from);
        } else if (!start.toLocalTime().isBefore(to)) {
            start = now.toLocalDate().plusDays(1).atTime(from);
        }
        int delay = TRAVEL_MINUTES + (emergency ? 0 : Math.max(0, team.queueLength()) * QUEUE_SLOT_MINUTES);
        LocalDateTime arrival = roundUp(start.plusMinutes(delay));
        LocalDateTime windowEnd = start.toLocalDate().atTime(to);
        if (!arrival.isBefore(windowEnd)) {
            arrival = roundUp(start.toLocalDate().plusDays(1).atTime(from).plusMinutes(delay));
        }
        return arrival;
    }

    private static LocalDateTime roundUp(LocalDateTime value) {
        int remainder = value.getMinute() % ROUND_MINUTES;
        return remainder == 0 ? value.withSecond(0).withNano(0)
                : value.plusMinutes(ROUND_MINUTES - remainder).withSecond(0).withNano(0);
    }

    /** 人话 ETA（今天/明天/后天/日期 + HH:mm） */
    static String humanEta(LocalDateTime arrival, LocalDateTime now) {
        LocalDate nowDate = now.toLocalDate();
        long days = ChronoUnit.DAYS.between(nowDate, arrival.toLocalDate());
        String prefix = switch ((int) days) {
            case 0 -> "今天";
            case 1 -> "明天";
            case 2 -> "后天";
            default -> arrival.getMonthValue() + "月" + arrival.getDayOfMonth() + "日";
        };
        return prefix + " " + HH_MM.format(arrival);
    }

    static String etaIso(LocalDateTime arrival) {
        return arrival.atZone(TicketTimes.BUSINESS_ZONE)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"));
    }
}
