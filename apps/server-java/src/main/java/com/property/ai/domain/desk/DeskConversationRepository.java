package com.property.ai.domain.desk;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.property.ai.bff.dto.DeskConversationStatus;
import com.property.ai.bff.dto.TicketPushChannel;
import com.property.ai.domain.ticket.TicketTimes;

/**
 * 坐席会话仓储（P4-012/013，进程内 demo 存储）。
 *
 * <h2>装载口径（P4 demo，务必在报告/文档标注 mock 语义）</h2>
 * <ul>
 *   <li><b>会话来源</b>：{@code mock/data/tickets.json} 的<b>未结单</b>（非 completed/reviewed/archived），
 *       最多 8 条；会话 ID 由工单号确定性派生（{@code WO-2026...-0031 → CONV-2026...-0031}）；</li>
 *   <li><b>等待起点</b>：进程内首次访问时按固定演示阶梯回填（{@code 96/84/132/47/330/210} 秒前进入队列），
 *       目的是一次演示就能看到「多会话排队 + 1 条超 SLA」；<b>不是</b>真实 IM 会话时间戳。
 *       P5 接会话存储后由真实 {@code enqueuedAt} 驱动，契约形状不变；</li>
 *   <li><b>在务会话</b>：另外取 2 条未结单预置为「已接管（agent-01）」，用于质检抽样/SLA 口径演示（待评分）；</li>
 *   <li><b>首响 SLA</b>：{@value #SLA_FIRST_RESPONSE_SECONDS} 秒（等待起点 + 300s = {@code slaDueAt}），
 *       与契约「首响 = 坐席第一条人工回复；超时以 slaDueAt 判定」一致（demo 用「接管即首响」近似）。</li>
 * </ul>
 *
 * <p>线程安全：所有读写走 {@code synchronized}（单实例 demo）；P5 迁 PostgreSQL 后由行锁/乐观锁替代。
 */
@Component
public class DeskConversationRepository {

    private static final Logger log = LoggerFactory.getLogger(DeskConversationRepository.class);

    /** 首响 SLA 窗口（秒） */
    public static final int SLA_FIRST_RESPONSE_SECONDS = 300;

    /** 等待中会话的演示等待阶梯（秒，按下标回填；330 &gt; 300 制造 1 条超 SLA 样本） */
    private static final int[] WAITING_LADDER_SECONDS = { 96, 84, 132, 47, 330, 210 };

    /** 预置在务会话的首响秒数（52/61 秒，均在 SLA 内） */
    private static final int[] TAKEN_FIRST_RESPONSE_SECONDS = { 52, 61 };

    /** 最多装载的会话数（未结单数量上限） */
    private static final int MAX_CONVERSATIONS = 8;

    /** 预置在务会话的接管坐席（演示账号；不是真实工号） */
    private static final String PRESEEDED_AGENT = "agent-01";

    private final DeskSeedData seedData;
    private final List<DeskConversation> conversations = new ArrayList<>();
    private boolean seeded;

    private Clock clock = Clock.system(TicketTimes.BUSINESS_ZONE);

    public DeskConversationRepository(DeskSeedData seedData) {
        this.seedData = seedData;
    }

    /** 测试注入固定时钟（等待秒数/SLA 断言用；生产不调用）。 */
    void setClockForTest(Clock clock) {
        this.clock = clock == null ? Clock.system(TicketTimes.BUSINESS_ZONE) : clock;
    }

    /** 测试复位（清空并允许按新时钟重新装载）。 */
    synchronized void resetForTest(Clock clock) {
        conversations.clear();
        seeded = false;
        setClockForTest(clock);
    }

    public Clock clock() {
        return clock;
    }

    public int slaFirstResponseSeconds() {
        return SLA_FIRST_RESPONSE_SECONDS;
    }

    /** 全部会话（装载顺序：等待中在前、在务会话在后）。 */
    public synchronized List<DeskConversation> all() {
        ensureSeeded();
        return List.copyOf(conversations);
    }

    /** 按会话 ID 查（不存在返回空）。 */
    public synchronized Optional<DeskConversation> find(String conversationId) {
        ensureSeeded();
        if (conversationId == null || conversationId.isBlank()) {
            return Optional.empty();
        }
        String normalized = conversationId.trim();
        return conversations.stream().filter(item -> normalized.equals(item.conversationId())).findFirst();
    }

    /** 当前会话数（诊断/测试用）。 */
    public synchronized int size() {
        return all().size();
    }

    // ------------------------------------------------------------------
    // 装载
    // ------------------------------------------------------------------

    private void ensureSeeded() {
        if (seeded) {
            return;
        }
        seeded = true;
        Instant now = clock.instant();
        Map<String, DeskSeedData.Household> households = seedData.householdIndex();
        List<DeskSeedData.SeedTicket> open = new ArrayList<>();
        for (DeskSeedData.SeedTicket ticket : seedData.tickets()) {
            if (!closed(ticket.status())) {
                open.add(ticket);
            }
        }
        int limit = Math.min(open.size(), MAX_CONVERSATIONS);
        int takenCount = Math.min(TAKEN_FIRST_RESPONSE_SECONDS.length, Math.max(0, limit - 1));
        int waitingCount = limit - takenCount;
        for (int index = 0; index < limit; index++) {
            DeskSeedData.SeedTicket ticket = open.get(index);
            DeskSeedData.Household household = households.get(ticket.roomCode());
            String ownerNameMasked = household == null || household.ownerNameMasked() == null
                    ? "业主"
                    : household.ownerNameMasked();
            String conversationId = conversationIdOf(ticket.ticketNo());
            if (index < waitingCount) {
                int waitSeconds = WAITING_LADDER_SECONDS[Math.min(index, WAITING_LADDER_SECONDS.length - 1)];
                Instant enqueuedAt = now.minusSeconds(waitSeconds);
                conversations.add(new DeskConversation(conversationId, ticket.roomCode(), ownerNameMasked,
                        TicketPushChannel.WECOM, DeskSeedData.preview(ticket.symptom()), ticket.category(),
                        enqueuedAt, enqueuedAt.plusSeconds(SLA_FIRST_RESPONSE_SECONDS),
                        DeskConversationStatus.WAITING, null, null));
                continue;
            }
            int takenIndex = index - waitingCount;
            int responseSeconds = TAKEN_FIRST_RESPONSE_SECONDS[Math.min(takenIndex,
                    TAKEN_FIRST_RESPONSE_SECONDS.length - 1)];
            // 在务会话：10/12 分钟前进入队列，接管发生在进入后 52/61 秒（首响在 SLA 内）
            Instant enqueuedAt = now.minus(Duration.ofSeconds(600 + takenIndex * 120L));
            conversations.add(new DeskConversation(conversationId, ticket.roomCode(), ownerNameMasked,
                    TicketPushChannel.WECOM, DeskSeedData.preview(ticket.symptom()), ticket.category(),
                    enqueuedAt, enqueuedAt.plusSeconds(SLA_FIRST_RESPONSE_SECONDS),
                    DeskConversationStatus.TAKEN_OVER, PRESEEDED_AGENT,
                    enqueuedAt.plusSeconds(responseSeconds)));
        }
        log.info("[desk] 坐席会话已装载 {} 条（等待 {} / 在务 {}；demo 阶梯等待，非真实 IM 会话时间戳）",
                conversations.size(), waitingCount, takenCount);
    }

    /** 契约「已完成的工单不再作为待接入会话」判定（P1 mock 与 ERP 状态文本都兼容）。 */
    private static boolean closed(String status) {
        if (status == null || status.isBlank()) {
            return true;
        }
        String normalized = status.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("completed") || normalized.equals("reviewed") || normalized.equals("archived");
    }

    /**
     * 会话 ID 口径：{@code WO-20261114-0031 → CONV-20261114-0031}（去掉 {@code WO-} 前缀再加 {@code CONV-}）；
     * 非工单号形态时回退 {@code CONV-<ticketNo>}，保证幂等稳定。
     */
    static String conversationIdOf(String ticketNo) {
        if (ticketNo == null || ticketNo.isBlank()) {
            return "CONV-UNKNOWN";
        }
        String trimmed = ticketNo.trim();
        if (trimmed.startsWith("WO-")) {
            return "CONV-" + trimmed.substring("WO-".length());
        }
        return "CONV-" + trimmed;
    }

    /** 诊断/报告用：会话 ID → 房号（不落姓名/联系方式）。 */
    public synchronized Map<String, String> conversationRooms() {
        Map<String, String> rows = new LinkedHashMap<>();
        for (DeskConversation conversation : all()) {
            rows.put(conversation.conversationId(), conversation.roomCode());
        }
        return rows;
    }
}
