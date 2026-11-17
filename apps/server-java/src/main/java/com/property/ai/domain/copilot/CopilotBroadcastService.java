package com.property.ai.domain.copilot;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.property.ai.bff.dto.CopilotBroadcastRequest;
import com.property.ai.bff.dto.CopilotBroadcastResult;
import com.property.ai.bff.dto.TicketPushChannel;
import com.property.ai.bff.dto.TicketPushStatus;
import com.property.ai.connector.PushChannel;
import com.property.ai.domain.ticket.DomainException;
import com.property.ai.domain.ticket.TicketTimes;

/**
 * 群服务：<b>人工确认后</b>的群发受理（P4-011，契约 v1.2.0 {@code POST /copilot/group/broadcast}）。
 *
 * <h2>红线（AGENTS.md NEVER #4/#5/#7 / 契约 §1.5）</h2>
 * <ul>
 *   <li>{@code confirmed} 必须为 {@code true}，否则 400 {@code E-1001}
 *       （{@code details.reason=not_confirmed}）——<b>人工确认是硬门槛</b>；</li>
 *   <li>正文含手机号/身份证/他人房号/欠费信息 → 400 {@code E-1001}
 *       （{@code details.reason=privacy_violation}）；</li>
 *   <li>只经 {@code services/mock-channel} 留痕（{@code template=group_notice}、{@code status=mocked}），
 *       <b>不接真实企微/微信、不产生真实投递</b>；</li>
 *   <li><b>不做群内自动回复</b>：本服务没有任何「读群消息 → 自动回」的路径，只受理人工审核过的公告文案。</li>
 * </ul>
 *
 * <h2>目标房号（{@code targetRooms}）</h2>
 * 省略 = 按演示群成员解析（{@code mock/data/households.json} 中 1 栋 1 单元的房号，最多 20 户）；
 * 显式传入时校验房号格式与房档存在性（不存在 → 400，避免「以为发了实际没发」）。
 * 每个目标房号一条 mock 通道受理记录（{@code pushIds} 可回溯）。
 */
@Service
public class CopilotBroadcastService {

    private static final Logger log = LoggerFactory.getLogger(CopilotBroadcastService.class);

    /** 群发正文最大长度（字符；公告文案，超出需走专题通知） */
    static final int MAX_CONTENT_CHARS = 500;
    /** 单次群发目标房号上限（防误群发全小区） */
    static final int MAX_TARGET_ROOMS = 50;
    /** 演示群成员口径：1 栋 1 单元（多项目/多群 P5 由群成员表替换） */
    private static final String DEMO_GROUP_BUILDING = "1栋";
    private static final String DEMO_GROUP_UNIT = "1单元";

    private static final DateTimeFormatter COMPACT_DAY =
            DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Pattern ROOM_CODE_PATTERN = Pattern.compile("^\\d{1,2}-\\d{1,2}-\\d{3,4}$");
    /** 正文扫描用房号形态（不锚定，命中「1-1-101」等房号即视为他人房号泄露） */
    private static final Pattern ROOM_CODE_SCAN = Pattern.compile("\\d{1,2}-\\d{1,2}-\\d{3,4}");
    private static final Pattern MOBILE_PATTERN = Pattern.compile("1[3-9]\\d{9}");
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("\\d{17}[\\dXx]|\\d{15}");
    private static final List<String> ARREARS_KEYWORDS = List.of("欠费", "欠缴", "滞纳金", "失信");

    private final CopilotDataRepository dataRepository;
    private final PushChannel pushChannel;

    private java.time.Clock clock = java.time.Clock.system(TicketTimes.BUSINESS_ZONE);

    public CopilotBroadcastService(CopilotDataRepository dataRepository, PushChannel pushChannel) {
        this.dataRepository = dataRepository;
        this.pushChannel = pushChannel;
    }

    /** 测试注入固定时钟（sentAt 断言用；生产不调用）。 */
    void setClockForTest(java.time.Clock clock) {
        this.clock = clock == null ? java.time.Clock.system(TicketTimes.BUSINESS_ZONE) : clock;
    }

    /**
     * 群发受理（写操作）。
     *
     * @return {@code pushIds}（mock 通道受理 ID）+ {@code status=mocked} + {@code sentAt}
     */
    public CopilotBroadcastResult broadcast(CopilotBroadcastRequest request, String traceId) {
        if (request == null) {
            throw DomainException.badRequest("请求体不能为空（content/confirmed 必填）",
                    Map.of("field", "body", "required", true));
        }
        if (!request.confirmed()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("reason", "not_confirmed");
            details.put("field", "confirmed");
            details.put("hint", "群发必须先由人工审核文案并显式确认（confirmed=true）");
            throw DomainException.badRequest("群发必须人工确认（confirmed=true）", details);
        }
        String content = request.content() == null ? "" : request.content().trim();
        if (content.isEmpty()) {
            throw DomainException.badRequest("群发正文 content 必填", Map.of("field", "content", "required",
                    true));
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            throw DomainException.badRequest("群发正文不能超过 " + MAX_CONTENT_CHARS + " 字符",
                    Map.of("field", "content", "length", content.length(), "maximum", MAX_CONTENT_CHARS));
        }
        List<String> violations = privacyViolations(content);
        if (!violations.isEmpty()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("reason", "privacy_violation");
            details.put("field", "content");
            details.put("matched", violations);
            details.put("hint", "群发正文不得含手机号/身份证/他人房号/欠费信息");
            throw DomainException.badRequest("群发正文含隐私信息，已拒绝", details);
        }
        List<String> targets = resolveTargets(request.targetRooms());
        String day = COMPACT_DAY.format(Instant.now().atZone(TicketTimes.BUSINESS_ZONE));
        String batchNo = "GRP-" + day + "-" + String.format("%04d", Math.abs(traceId == null ? 0
                : traceId.hashCode()) % 10000);
        List<String> pushIds = new ArrayList<>(targets.size());
        for (String roomCode : targets) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("content", content);
            payload.put("batchNo", batchNo);
            payload.put("roomMasked", maskRoom(roomCode));
            payload.put("mock", true);
            PushChannel.PushReceipt receipt = pushChannel.push(new PushChannel.PushMessage(
                    TicketPushChannel.WECOM.value(), batchNo, "group_notice", payload, maskRoom(roomCode)));
            pushIds.add(receipt == null ? "PUSH-UNKNOWN" : receipt.pushId());
        }
        String sentAt = TicketTimes.iso(clock.instant());
        log.info("[copilot] broadcast batch={} rooms={} pushIds={} traceId={}", batchNo, targets.size(),
                pushIds.size(), traceId);
        return new CopilotBroadcastResult(List.copyOf(pushIds), TicketPushStatus.MOCKED, sentAt);
    }

    /**
     * 目标房号解析：省略 = 演示群成员（1 栋 1 单元）；显式传入则校验格式与房档存在性。
     */
    List<String> resolveTargets(List<String> targetRooms) {
        List<String> targets = new ArrayList<>();
        if (targetRooms == null || targetRooms.isEmpty()) {
            for (CopilotDataRepository.Household household : dataRepository.households()) {
                if (DEMO_GROUP_BUILDING.equals(household.building())
                        && DEMO_GROUP_UNIT.equals(household.unit())) {
                    targets.add(household.roomCode());
                }
            }
            if (targets.isEmpty()) {
                throw DomainException.badRequest("无法解析群成员房号（演示房档缺失），请显式传 targetRooms",
                        Map.of("field", "targetRooms", "reason", "group_members_unavailable"));
            }
            return List.copyOf(targets);
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String raw : targetRooms) {
            String roomCode = raw == null ? "" : raw.trim().replace('－', '-');
            if (!ROOM_CODE_PATTERN.matcher(roomCode).matches()) {
                throw DomainException.badRequest("targetRooms 含非法房号：" + raw,
                        Map.of("field", "targetRooms", "value", raw == null ? "" : raw,
                                "format", "楼栋-单元-房间，如 1-1-101"));
            }
            if (dataRepository.household(roomCode).isEmpty()) {
                throw DomainException.badRequest("targetRooms 含不存在的房号：" + roomCode,
                        Map.of("field", "targetRooms", "value", roomCode, "reason", "unknown_room"));
            }
            unique.add(roomCode);
        }
        if (unique.size() > MAX_TARGET_ROOMS) {
            throw DomainException.badRequest("单次群发目标房号不能超过 " + MAX_TARGET_ROOMS,
                    Map.of("field", "targetRooms", "size", unique.size(), "maximum", MAX_TARGET_ROOMS));
        }
        return List.copyOf(unique);
    }

    /** 隐私违规命中项（手机号/身份证/房号/欠费关键词；只回命中类型，不回原文）。 */
    static List<String> privacyViolations(String content) {
        List<String> matched = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return matched;
        }
        Matcher mobile = MOBILE_PATTERN.matcher(content);
        if (mobile.find()) {
            matched.add("mobile");
        }
        Matcher idCard = ID_CARD_PATTERN.matcher(content);
        if (idCard.find()) {
            matched.add("id_card");
        }
        Matcher room = ROOM_CODE_SCAN.matcher(content);
        if (room.find()) {
            matched.add("room_code");
        }
        for (String keyword : ARREARS_KEYWORDS) {
            if (content.contains(keyword)) {
                matched.add("arrears");
                break;
            }
        }
        return matched;
    }

    /** 房号掩码（保留楼栋-单元，房间号打码；不落完整房号到 mock 通道 toMasked）。 */
    private static String maskRoom(String roomCode) {
        if (roomCode == null || roomCode.isBlank()) {
            return "room";
        }
        int index = roomCode.lastIndexOf('-');
        return index <= 0 ? roomCode.substring(0, 1) + "**" : roomCode.substring(0, index) + "-**";
    }
}
