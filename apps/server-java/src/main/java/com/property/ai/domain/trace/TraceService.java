package com.property.ai.domain.trace;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.property.ai.audit.AuditService;
import com.property.ai.bff.api.ticket.ApiSupport;
import com.property.ai.bff.dto.AuditEvent;
import com.property.ai.bff.dto.ErrorCode;
import com.property.ai.bff.dto.TraceDetail;
import com.property.ai.bff.dto.TraceEntry;
import com.property.ai.bff.dto.TraceEvidence;
import com.property.ai.bff.dto.TraceSearchItem;
import com.property.ai.bff.dto.TraceSearchResult;
import com.property.ai.domain.ticket.DomainException;
import com.property.ai.domain.ticket.TicketTimes;

/**
 * 全链路回溯服务（P4-018，契约 v1.2.0 {@code /trace/*}）。
 *
 * <h2>数据源（唯一事实源 = 审计事件）</h2>
 * 聚合 {@link AuditService} 的进程内审计事件：一次请求的业务事件（写审计 / 403 拒绝 / 登录登出）
 * 与手工贯穿的 {@code X-Trace-Id} 调用共享同一 {@code traceId}，按时间升序组成时间线。
 * <b>不编造节点</b>：无事件 → 404 {@code E-3001}。
 *
 * <h2>证据包脱敏（契约 {@code TraceEvidence.maskedFields}）</h2>
 * 导出前对 {@code resourceId} 做房号打码（{@code 1-1-101 → 1-1-***}），响应固定声明
 * {@code maskedFields = [ownerPhone, ownerName, roomCode]}（审计本身不落手机号/姓名原文）；
 * {@code checksum} = 脱敏后内容 SHA-256 前 16 位（举证一致性）。
 *
 * <p><b>demo 口径</b>：P4 仍以进程内审计为源（重启清空、上限 2000 条），P5 迁 ES/对象存储后
 * 改为索引查询，契约形状不变。
 */
@Service
public class TraceService {

    private static final Logger log = LoggerFactory.getLogger(TraceService.class);

    /** 证据包固定声明的脱敏字段（与契约示例一致；审计不落原文，只声明脱敏策略） */
    static final List<String> EVIDENCE_MASKED_FIELDS = List.of("ownerPhone", "ownerName", "roomCode");
    /** CSV 表头（契约 §1.5 evidence 描述，顺序固定） */
    static final List<String> CSV_HEADER = List.of("seq", "at", "actorId", "role", "action", "resourceType",
            "resourceId", "result", "durationMs");

    private static final Pattern ROOM_CODE_PATTERN = Pattern.compile("^\\d{1,2}-\\d{1,2}-\\d{3,4}$");

    private final AuditService auditService;

    public TraceService(AuditService auditService) {
        this.auditService = auditService;
    }

    // ==================================================================
    // 详情（时间线）
    // ==================================================================

    /** 链路详情：{@code entries[]} 按时间升序；无数据 404 {@code E-3001}。 */
    public TraceDetail detail(String traceId) {
        String normalized = requireTraceId(traceId);
        List<AuditEvent> events = auditService.byTraceId(normalized);
        if (events.isEmpty()) {
            throw DomainException.notFound(ErrorCode.E_3001, "链路不存在：" + normalized,
                    Map.of("traceId", normalized));
        }
        List<TraceEntry> entries = entries(events, false);
        return new TraceDetail(normalized, entries, entries.isEmpty() ? null : entries.get(0).at(),
                entries.isEmpty() ? null : entries.get(entries.size() - 1).at(), true);
    }

    // ==================================================================
    // 证据包（json / csv）
    // ==================================================================

    /** 证据包（JSON 形态）：脱敏 + checksum。 */
    public TraceEvidence evidence(String traceId) {
        String normalized = requireTraceId(traceId);
        List<AuditEvent> events = auditService.byTraceId(normalized);
        if (events.isEmpty()) {
            throw DomainException.notFound(ErrorCode.E_3001, "链路不存在：" + normalized,
                    Map.of("traceId", normalized));
        }
        List<TraceEntry> masked = entries(events, true);
        String generatedAt = TicketTimes.nowIso();
        return new TraceEvidence(normalized, generatedAt, checksum(masked), masked,
                EVIDENCE_MASKED_FIELDS, true);
    }

    /** 证据包（CSV 文件流内容，UTF-8 BOM 由 {@link TraceCsvExporter} 追加）。 */
    public byte[] evidenceCsv(String traceId) {
        String normalized = requireTraceId(traceId);
        List<AuditEvent> events = auditService.byTraceId(normalized);
        if (events.isEmpty()) {
            throw DomainException.notFound(ErrorCode.E_3001, "链路不存在：" + normalized,
                    Map.of("traceId", normalized));
        }
        TraceEvidence evidence = new TraceEvidence(normalized, TicketTimes.nowIso(),
                checksum(entries(events, true)), entries(events, true), EVIDENCE_MASKED_FIELDS, true);
        return TraceCsvExporter.export(evidence).getBytes(StandardCharsets.UTF_8);
    }

    /** CSV 下载文件名（契约 {@code Content-Disposition} 口径）。 */
    public String csvFileName(String traceId) {
        return "trace-" + requireTraceId(traceId) + ".csv";
    }

    // ==================================================================
    // 检索
    // ==================================================================

    /**
     * 回溯检索（按 actorId/action/时间窗过滤，按 {@code lastAt} 降序分页）。
     *
     * <p>过滤口径：{@code actorId}/{@code action} 命中链路上<b>任一</b>事件即算命中；时间窗按
     * {@code lastAt}（链路最后时间）闭区间过滤（契约 description）。
     */
    public TraceSearchResult search(String actorId, String action, String fromParam, String toParam, int page,
            int pageSize) {
        ApiSupport.requirePageParams(page, pageSize);
        Instant from = parseDateTime("from", fromParam);
        Instant to = parseDateTime("to", toParam);
        if (from != null && to != null && from.isAfter(to)) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("field", "from");
            details.put("reason", "invalid_range");
            details.put("from", fromParam);
            details.put("to", toParam);
            throw DomainException.badRequest("from 不能晚于 to", details);
        }
        String actorFilter = actorId == null || actorId.isBlank() ? null : actorId.trim();
        String actionFilter = action == null || action.isBlank() ? null : action.trim().toLowerCase(Locale.ROOT);

        Map<String, List<AuditEvent>> byTrace = new LinkedHashMap<>();
        for (AuditEvent event : auditService.snapshot()) {
            if (event.traceId() == null || event.traceId().isBlank()) {
                continue;
            }
            byTrace.computeIfAbsent(event.traceId(), key -> new ArrayList<>()).add(event);
        }
        List<TraceSearchItem> items = new ArrayList<>();
        for (Map.Entry<String, List<AuditEvent>> entry : byTrace.entrySet()) {
            List<AuditEvent> events = sortedAscending(entry.getValue());
            if (!matches(events, actorFilter, actionFilter)) {
                continue;
            }
            String lastAt = events.get(events.size() - 1).at();
            Instant lastInstant = TicketTimes.parseInstant(lastAt);
            if (from != null && (lastInstant == null || lastInstant.isBefore(from))) {
                continue;
            }
            if (to != null && (lastInstant == null || lastInstant.isAfter(to))) {
                continue;
            }
            AuditEvent first = events.get(0);
            items.add(new TraceSearchItem(entry.getKey(), first.at(), lastAt, first.actorId(),
                    first.action() == null ? null : first.action().value(), events.size()));
        }
        items.sort(Comparator.comparing(TraceSearchItem::lastAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(TraceSearchItem::traceId));
        int total = items.size();
        int offset = (page - 1) * pageSize;
        List<TraceSearchItem> pageItems;
        if (offset >= total) {
            pageItems = List.of();
        } else {
            pageItems = List.copyOf(items.subList(offset, Math.min(total, offset + pageSize)));
        }
        log.debug("[trace] search actor={} action={} from={} to={} total={}", actorFilter, actionFilter, from,
                to, total);
        return new TraceSearchResult(pageItems, page, pageSize, total);
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private static String requireTraceId(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            throw DomainException.badRequest("traceId 必填", Map.of("field", "traceId", "required", true));
        }
        return traceId.trim();
    }

    /** 审计事件 → 时间线节点（按 at 升序；{@code mask} = 证据包导出，resourceId 房号打码）。 */
    private static List<TraceEntry> entries(List<AuditEvent> events, boolean mask) {
        List<TraceEntry> entries = new ArrayList<>(events.size());
        for (AuditEvent event : sortedAscending(events)) {
            String resourceId = event.resourceId();
            if (mask && resourceId != null && ROOM_CODE_PATTERN.matcher(resourceId).matches()) {
                resourceId = maskRoom(resourceId);
            }
            entries.add(new TraceEntry(
                    event.at(),
                    event.actorId(),
                    event.actorRole(),
                    event.action() == null ? null : event.action().value(),
                    event.resourceType() == null ? null : event.resourceType().value(),
                    resourceId,
                    event.result(),
                    null));
        }
        return List.copyOf(entries);
    }

    /**
     * 按时间升序（同秒事件用事件 ID 兜底：{@code AUD-yyyyMMdd-NNNN} 序号单调递增，
     * 保证同一秒内的节点顺序与落库顺序一致，时间线可复算）。
     */
    private static List<AuditEvent> sortedAscending(List<AuditEvent> events) {
        List<AuditEvent> sorted = new ArrayList<>(events);
        sorted.sort(Comparator.comparing(AuditEvent::at, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(AuditEvent::eventId, Comparator.nullsLast(Comparator.naturalOrder())));
        return sorted;
    }

    private static boolean matches(List<AuditEvent> events, String actorFilter, String actionFilter) {
        if (actorFilter == null && actionFilter == null) {
            return true;
        }
        for (AuditEvent event : events) {
            if (actorFilter != null && !actorFilter.equals(event.actorId())) {
                continue;
            }
            if (actionFilter != null && (event.action() == null
                    || !actionFilter.equals(event.action().value().toLowerCase(Locale.ROOT)))) {
                continue;
            }
            return true;
        }
        return false;
    }

    /** 内容校验和（SHA-256 前 16 位；对脱敏后的时间线规范化串计算）。 */
    static String checksum(List<TraceEntry> entries) {
        StringBuilder canonical = new StringBuilder();
        for (TraceEntry entry : entries) {
            canonical.append(entry.at()).append('|')
                    .append(entry.actorId()).append('|')
                    .append(entry.role() == null ? "" : entry.role().value()).append('|')
                    .append(entry.action()).append('|')
                    .append(entry.resourceType()).append('|')
                    .append(entry.resourceId()).append('|')
                    .append(entry.result() == null ? "" : entry.result().value()).append('\n');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(String.format("%02x", value));
            }
            return hex.substring(0, 16);
        } catch (NoSuchAlgorithmException ex) {
            return "0000000000000000";
        }
    }

    private static String maskRoom(String roomCode) {
        int index = roomCode.lastIndexOf('-');
        return index <= 0 ? "**" : roomCode.substring(0, index) + "-***";
    }

    /** 日期时间解析：ISO 8601（带偏移）优先；纯日期按业务时区 00:00 解释。非法 → 400。 */
    private static Instant parseDateTime(String field, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        Instant instant = TicketTimes.parseInstant(value);
        if (instant != null) {
            return instant;
        }
        try {
            LocalDate date = LocalDate.parse(value);
            return LocalDateTime.of(date, LocalTime.MIN).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
        } catch (DateTimeParseException ignored) {
            // 落到统一 400
        }
        throw DomainException.badRequest(field + " 必须是 ISO 8601 时间（如 2026-11-16T00:00:00+08:00）",
                Map.of("field", field, "value", raw, "format", "date-time"));
    }
}
