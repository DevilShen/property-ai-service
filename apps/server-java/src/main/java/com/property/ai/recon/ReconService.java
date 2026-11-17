package com.property.ai.recon;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.property.ai.bff.dto.BillInfo;
import com.property.ai.bff.dto.ErrorCode;
import com.property.ai.bff.dto.ReconDiffItem;
import com.property.ai.bff.dto.ReconDiffKind;
import com.property.ai.bff.dto.ReconReport;
import com.property.ai.bff.dto.ReconRunListResult;
import com.property.ai.bff.dto.ReconRunRequest;
import com.property.ai.bff.dto.ReconRunResult;
import com.property.ai.bff.dto.ReconRunStatus;
import com.property.ai.bff.dto.ReconScope;
import com.property.ai.bff.dto.RoomInfo;
import com.property.ai.bff.dto.TicketListItem;
import com.property.ai.connector.ConnectorException;
import com.property.ai.bff.dto.EventType;
import com.property.ai.domain.event.EventStore;
import com.property.ai.domain.project.ProjectService;
import com.property.ai.domain.ticket.DomainException;

import jakarta.annotation.PreDestroy;

/**
 * 只读对账服务（P2-013）：异步受理 {@code POST /api/v1/recon/run} → 轮询 {@code GET /recon/report/{runId}}。
 *
 * <h2>对账口径（写进报告 {@code _dev/reports/P2-对账-*.md} 的同源版本）</h2>
 * <p><b>事实源</b>：ERP 实时只读（经连接器 {@code HttpErpClient/ErpConnector}，不重算、不补齐、不落库）。
 * P2 第 1 周 ERP = {@code services/mock-erp} 测试替身，因此所有报告/响应 {@code mock:true}。
 * <p><b>对比对象</b>：平台侧展示快照 / 规范映射结果（BFF 读路径返回的 {@code BillInfo/RoomInfo/TicketListItem}）。
 * 逐字段校验清单见 {@link ReconEvaluator}。
 * <p><b>统计</b>：{@code checked/matched/diffCount/diffRate}（= diffCount/checked）与
 * {@code passed = completed && checked>0 && diffRate <= threshold}；阈值默认 {@code 0.02}（P2 门禁 ≤2%）。
 * <p><b>样本</b>：{@code sampleSize} 默认 200、上限 1000；账期默认当月（mock-erp 本期 2026-11），
 * 可用 {@code property.recon.period} 覆盖；{@code scope} 必填。
 *
 * <p><b>失败语义（P2-014 断链）</b>：
 * <ul>
 *   <li>ERP 批次读取失败（超时/熔断/降级）→ run {@code status=failed}、{@code passed=false}、
 *       {@code items} 追加 {@code missing/erp_read} 说明原因；<b>绝不返回假数据、绝不把失败当通过</b>；</li>
 *   <li>单条对象读取失败 → 该对象记 1 条差异（{@code missing/erp.read}），计入 diffRate；
 *       样本内失败越多差异率越高 → 门禁自然失败（防「一半数据读不到却通过」）。</li>
 * </ul>
 *
 * <p>异步实现：进程内固定线程池（P2 单实例）。run 记录保留 1 小时、最多 200 条；
 * {@code Idempotency-Key} 可选，同键 24h 内重复触发返回同一 runId（契约 202）。
 */
@Service
public class ReconService {

    private static final Logger log = LoggerFactory.getLogger(ReconService.class);

    static final int DEFAULT_SAMPLE_SIZE = 200;
    static final int MAX_SAMPLE_SIZE = 1000;
    static final double DEFAULT_THRESHOLD = 0.02;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 64;
    private static final Duration IDEMPOTENCY_WINDOW = Duration.ofHours(24);
    private static final long RUN_RETENTION_MS = 3_600_000L;
    private static final int MAX_RUNS = 200;

    private final ReconErpReader reader;
    private final double threshold;
    private final String periodOverride;
    private final Executor executor;

    /** outbox 事件存储（P5-019；可选注入：保持既有单测构造不破，未注入时只记日志） */
    private EventStore eventStore;

    private final Map<String, ReconRunState> runs = new ConcurrentHashMap<>();
    private final Map<String, IdempotencyEntry> idempotency = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger(0);

    @Autowired
    public ReconService(ReconErpReader reader,
            @Value("${property.recon.diff-threshold:0.02}") double diffThreshold,
            @Value("${property.recon.period:}") String periodOverride,
            @Value("${property.recon.worker-threads:2}") int workerThreads) {
        this(reader, diffThreshold, periodOverride, newExecutor(workerThreads));
    }

    /** 单测构造：注入确定性线程池/阈值 */
    ReconService(ReconErpReader reader, double threshold, String periodOverride, Executor executor) {
        this.reader = reader;
        this.threshold = threshold <= 0 ? DEFAULT_THRESHOLD : threshold;
        this.periodOverride = periodOverride;
        this.executor = executor;
    }

    /** 事件存储注入（可选：P5-019 outbox；单测不构造时为 null） */
    @Autowired(required = false)
    public void setEventStore(EventStore eventStore) {
        this.eventStore = eventStore;
    }

    @PreDestroy
    void shutdown() {
        if (executor instanceof ExecutorService service) {
            service.shutdownNow();
        }
    }

    // ==================================================================
    // 受理 / 查询
    // ==================================================================

    /**
     * 受理对账（异步）：参数校验 → 生成 runId → 提交线程池 → 立即返回 202 载荷。
     *
     * @param request       契约 {@code ReconRunRequest}（scope 必填；sampleSize 默认 200 / 1—1000）
     * @param idempotencyKey 可选幂等键（≤64 字符；同键 24h 内返回同一 runId）
     * @param traceId       全链路 traceId（透传到每个 ERP 出站请求）
     */
    public ReconRunResult startRun(ReconRunRequest request, String idempotencyKey, String traceId) {
        ReconRunRequest body = request == null ? new ReconRunRequest(null, null) : request;
        if (body.scope() == null) {
            throw DomainException.badRequest("scope 必填（room|bill|ticket）", Map.of("field", "scope"));
        }
        int sampleSize = body.sampleSize() == null ? DEFAULT_SAMPLE_SIZE : body.sampleSize();
        if (sampleSize < 1 || sampleSize > MAX_SAMPLE_SIZE) {
            throw DomainException.badRequest("sampleSize 必须在 1—" + MAX_SAMPLE_SIZE + " 之间",
                    Map.of("field", "sampleSize", "sampleSize", sampleSize, "maximum", MAX_SAMPLE_SIZE));
        }
        String key = normalizeIdempotencyKey(idempotencyKey);
        if (key != null) {
            ReconRunResult replay = replay(key);
            if (replay != null) {
                log.info("[recon] 幂等复用 runId={} key={} traceId={}", replay.runId(), key, traceId);
                return replay;
            }
        }

        purgeOldRuns();
        String runId = nextRunId();
        ReconRunState run = new ReconRunState(runId, body.scope(), sampleSize, ReconTimes.nowIso());
        runs.put(runId, run);
        if (key != null) {
            IdempotencyEntry prior = idempotency.putIfAbsent(key,
                    new IdempotencyEntry(runId, Instant.now().toEpochMilli()));
            if (prior != null) {
                runs.remove(runId);
                ReconRunState other = runs.get(prior.runId());
                if (other != null) {
                    return other.result();
                }
            }
        }
        log.info("[recon] 受理 runId={} scope={} sampleSize={} traceId={}", runId, body.scope(), sampleSize,
                traceId);
        executor.execute(() -> execute(run, traceId));
        return run.result();
    }

    /** 查询对账报告；runId 不存在 → 404 E-3001（契约） */
    public ReconReport report(String runId) {
        ReconRunState run = runId == null ? null : runs.get(runId);
        if (run == null) {
            throw DomainException.notFound(ErrorCode.E_3001, "对账任务不存在：" + runId,
                    Map.of("runId", runId == null ? "" : runId));
        }
        return run.snapshot(threshold);
    }

    /** 当前判定阈值（P2 门禁 0.02） */
    public double threshold() {
        return threshold;
    }

    /**
     * 发 outbox 事件 recon.completed（P5-019，异步 run 完成时）：payload 脱敏——
     * 只放 runId/scope/计数/差异率/结论，<b>不放差异明细与房号/金额原文</b>（明细走 recon 报告接口受 audit.read 约束）。
     */
    private void publishCompletedEvent(ReconReport report) {
        if (eventStore == null) {
            log.debug("[recon] 事件存储未注入，跳过 recon.completed runId={}", report.runId());
            return;
        }
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("runId", report.runId());
        payload.put("scope", report.scope() == null ? null : report.scope().value());
        payload.put("checked", report.checked());
        payload.put("matched", report.matched());
        payload.put("diffCount", report.diffCount());
        payload.put("diffRate", report.diffRate());
        payload.put("passed", report.passed());
        eventStore.publish(EventType.RECON_COMPLETED, ProjectService.DEFAULT_PROJECT_ID, payload);
    }

    // ==================================================================
    // CSV 导出（P3-031，契约 v1.1.0 {@code reconReportExport}）
    // ==================================================================

    /**
     * 导出对账差异 CSV（文件流例外：2xx 由 Controller 直出 text/csv，错误仍统一 ErrorResponse）。
     *
     * <p>错误语义（契约既有 17 类错误码，不新造）：
     * <ul>
     *   <li>{@code format} 非 {@code csv}（省略 = csv）→ 400 {@code E-1001}
     *       {@code details.reason=unsupported_format}；</li>
     *   <li>{@code runId} 不存在 → 404 {@code E-3001}；</li>
     *   <li>报告未完成（queued / running / failed）→ 409 {@code E-3002}
     *       {@code details.reason=report_not_completed}——<b>不导出半成品、不把失败当完成</b>。</li>
     * </ul>
     *
     * @param runId  对账任务 ID（形如 RECON-yyyyMMdd-NNNN）
     * @param format 导出格式（P3 仅 csv）
     */
    public CsvExport exportCsv(String runId, String format) {
        String normalized = format == null || format.isBlank() ? "csv" : format.trim().toLowerCase(Locale.ROOT);
        if (!"csv".equals(normalized)) {
            throw DomainException.badRequest("对账报告导出仅支持 format=csv（P3 唯一格式）",
                    Map.of("field", "format", "value", format == null ? "" : format,
                            "allowed", List.of("csv"), "reason", "unsupported_format"));
        }
        ReconReport report = report(runId);
        if (report.status() != ReconRunStatus.COMPLETED) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("reason", "report_not_completed");
            details.put("runId", report.runId());
            details.put("status", report.status() == null ? "" : report.status().value());
            details.put("hint", "对账未完成（queued/running/failed）不可导出，请等待 status=completed");
            throw DomainException.conflict("对账报告未完成（status="
                    + (report.status() == null ? "" : report.status().value()) + "），不可导出", details);
        }
        return new CsvExport(report.runId(), ReconCsvExporter.toCsv(report));
    }

    /** CSV 导出结果（runId 用于响应头 filename；bytes 含 UTF-8 BOM） */
    public record CsvExport(String runId, byte[] bytes) {
    }

    /**
     * 对账历史分页（P2-027，契约 v0.3.2 {@code GET /api/v1/recon/runs}）。
     *
     * <p>复用本服务已有 run 存储（不新增持久化）：按 {@code startedAt} 倒序返回受理记录；
     * runId 形如 {@code RECON-yyyyMMdd-NNNN} 且单调递增，因此 runId 倒序等价「最近受理优先」。
     * 无记录返回空列表（不造数）；明细仍走 {@link #report(String)}。
     */
    public ReconRunListResult listRuns(int page, int pageSize) {
        List<ReconRunResult> all = new ArrayList<>();
        for (ReconRunState run : runs.values()) {
            all.add(run.result());
        }
        all.sort(java.util.Comparator.comparing(ReconRunResult::runId).reversed());
        int total = all.size();
        int from = Math.min(Math.max(0, (page - 1) * pageSize), total);
        int to = Math.min(from + pageSize, total);
        return new ReconRunListResult(List.copyOf(all.subList(from, to)), page, pageSize, total);
    }

    // ==================================================================
    // 异步执行
    // ==================================================================

    private void execute(ReconRunState run, String traceId) {
        long startedAt = System.currentTimeMillis();
        run.markRunning();
        String period = resolvePeriod();
        try {
            switch (run.scope()) {
                case BILL -> reconcileBills(run, period, traceId);
                case ROOM -> reconcileRooms(run, period, traceId);
                case TICKET -> reconcileTickets(run, traceId);
            }
            run.complete();
            ReconReport report = run.snapshot(threshold);
            log.info("[recon] 完成 runId={} scope={} checked={} matched={} diffCount={} diffRate={} passed={} 用时={}ms",
                    report.runId(), report.scope(), report.checked(), report.matched(), report.diffCount(),
                    report.diffRate(), report.passed(), System.currentTimeMillis() - startedAt);
            publishCompletedEvent(report);
        } catch (ConnectorException ex) {
            run.fail(ex.code().value(), ex.getMessage());
            log.warn("[recon] 失败 runId={} ERP 不可用：{} {} traceId={}", run.result().runId(), ex.code().value(),
                    ex.getMessage(), traceId);
        } catch (RuntimeException ex) {
            run.fail(ErrorCode.E_9001.value(), ex.getClass().getSimpleName() + ": " + ex.getMessage());
            log.error("[recon] 异常 runId={} traceId={}", run.result().runId(), traceId, ex);
        }
    }

    /** bill：账期账单样本 → 逐条 ERP 原值 vs 平台快照 */
    private void reconcileBills(ReconRunState run, String period, String traceId) {
        List<JsonNode> rows = reader.readBillRows(period, run.sampleSize(), traceId);
        log.info("[recon] bill 样本 {} 条（账期 {}）runId={}", rows.size(), period, run.result().runId());
        for (JsonNode raw : rows) {
            String roomCode = text(raw, "roomNo", "roomCode");
            String billNo = text(raw, "billNo");
            String billPeriod = text(raw, "period");
            String key = billNo != null ? billNo : "BILL-" + (roomCode == null ? "UNKNOWN" : roomCode);
            List<ReconDiffItem> diffs;
            try {
                BillInfo display = reader.readBillDisplay(roomCode, billPeriod == null ? period : billPeriod, traceId);
                diffs = ReconEvaluator.evaluateBill(raw, display);
            } catch (ConnectorException ex) {
                diffs = List.of(readFailure(key, roomCode, billNo, null, "bill.query", ex));
            }
            run.recordObject(diffs);
        }
    }

    /** room：以账单样本房号为房档样本 → 房号/面积/车位/绑定态 + 面积费用口径 */
    private void reconcileRooms(ReconRunState run, String period, String traceId) {
        List<JsonNode> billRows = reader.readBillRows(period, run.sampleSize(), traceId);
        boolean expectPublicEnergy = period.equals(ReconTimes.currentPeriod());
        log.info("[recon] room 样本 {} 条（账期 {}，公摊检查={}）runId={}", billRows.size(), period,
                expectPublicEnergy, run.result().runId());
        for (JsonNode billRow : billRows) {
            String roomCode = text(billRow, "roomNo", "roomCode");
            List<ReconDiffItem> diffs;
            try {
                JsonNode erpRoom = reader.readRoomRow(roomCode, traceId);
                RoomInfo display = reader.readRoomDisplay(roomCode, traceId);
                diffs = ReconEvaluator.evaluateRoom(erpRoom, display, billRow, expectPublicEnergy);
            } catch (ConnectorException ex) {
                diffs = List.of(readFailure(roomCode == null ? "ROOM-UNKNOWN" : roomCode, roomCode, null, null,
                        "room.query", ex));
            }
            run.recordObject(diffs);
        }
    }

    /** ticket：ERP 工单列表样本 → 状态映射 + 字段漂移 */
    private void reconcileTickets(ReconRunState run, String traceId) {
        List<JsonNode> erpRows = reader.readTicketRows(run.sampleSize(), traceId);
        List<TicketListItem> displayRows = reader.readTicketDisplay(run.sampleSize(), traceId);
        Map<String, TicketListItem> displayByNo = new LinkedHashMap<>();
        for (TicketListItem item : displayRows) {
            if (item != null && item.ticketNo() != null) {
                displayByNo.put(item.ticketNo(), item);
            }
        }
        log.info("[recon] ticket 样本 {} 条（平台列表 {} 条）runId={}", erpRows.size(), displayRows.size(),
                run.result().runId());
        for (JsonNode raw : erpRows) {
            String ticketNo = text(raw, "ticketNo");
            String roomCode = text(raw, "roomNo", "roomCode");
            String key = ticketNo == null ? "TICKET-UNKNOWN" : ticketNo;
            List<ReconDiffItem> diffs;
            try {
                diffs = ReconEvaluator.evaluateTicket(raw, displayByNo.get(key));
            } catch (ConnectorException ex) {
                diffs = List.of(readFailure(key, roomCode, null, ticketNo, "ticket.list", ex));
            }
            run.recordObject(diffs);
        }
    }

    private ReconDiffItem readFailure(String key, String roomCode, String billNo, String ticketNo,
            String capability, ConnectorException ex) {
        return new ReconDiffItem(key, ReconDiffKind.MISSING, capability + ".read", "ERP 只读可读",
                ex.code().value() + "：" + ex.getMessage(), roomCode, billNo, ticketNo);
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    private String resolvePeriod() {
        String override = periodOverride == null ? "" : periodOverride.trim();
        return override.isEmpty() ? ReconTimes.currentPeriod() : override;
    }

    private String normalizeIdempotencyKey(String raw) {
        String key = raw == null ? "" : raw.trim();
        if (key.isEmpty()) {
            return null;
        }
        if (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw DomainException.badRequest("Idempotency-Key 长度不能超过 " + MAX_IDEMPOTENCY_KEY_LENGTH + " 字符",
                    Map.of("field", "Idempotency-Key", "length", key.length(),
                            "maximum", MAX_IDEMPOTENCY_KEY_LENGTH));
        }
        return key;
    }

    private ReconRunResult replay(String key) {
        IdempotencyEntry entry = idempotency.get(key);
        if (entry == null) {
            return null;
        }
        long ageMs = Instant.now().toEpochMilli() - entry.createdAtMs();
        if (ageMs > IDEMPOTENCY_WINDOW.toMillis()) {
            idempotency.remove(key, entry);
            return null;
        }
        ReconRunState run = runs.get(entry.runId());
        if (run == null) {
            idempotency.remove(key, entry);
            return null;
        }
        return run.result();
    }

    private void purgeOldRuns() {
        if (runs.size() < MAX_RUNS) {
            long now = System.currentTimeMillis();
            runs.entrySet().removeIf(entry -> entry.getValue().finished()
                    && now - entry.getValue().finishedEpochMs() > RUN_RETENTION_MS);
            return;
        }
        List<String> finishedIds = new ArrayList<>();
        long now = System.currentTimeMillis();
        runs.forEach((id, run) -> {
            if (run.finished() && now - run.finishedEpochMs() > RUN_RETENTION_MS) {
                finishedIds.add(id);
            }
        });
        finishedIds.forEach(runs::remove);
        if (runs.size() >= MAX_RUNS) {
            runs.entrySet().stream()
                    .filter(entry -> entry.getValue().finished())
                    .sorted((left, right) -> Long.compare(left.getValue().finishedEpochMs(),
                            right.getValue().finishedEpochMs()))
                    .limit(Math.max(1, runs.size() - MAX_RUNS + 1))
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(runs::remove);
        }
    }

    private String nextRunId() {
        int seq = sequence.incrementAndGet();
        return String.format("RECON-%s-%04d", ReconTimes.todayCompact(), seq);
    }

    private static String text(JsonNode node, String... fields) {
        if (node == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull()) {
                String text = value.asText();
                if (text != null && !text.isEmpty()) {
                    return text;
                }
            }
        }
        return null;
    }

    private static ExecutorService newExecutor(int workerThreads) {
        AtomicInteger threads = new AtomicInteger(0);
        return Executors.newFixedThreadPool(Math.max(1, workerThreads), runnable -> {
            Thread thread = new Thread(runnable, "recon-runner-" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    private record IdempotencyEntry(String runId, long createdAtMs) {
    }
}
