package com.property.ai.bff.api.degrade;

import java.time.OffsetDateTime;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.property.ai.bff.api.ApiResponse;
import com.property.ai.bff.api.ticket.ApiSupport;
import com.property.ai.bff.dto.DegradeLedgerResult;
import com.property.ai.connector.ConnectorTimes;
import com.property.ai.connector.DegradeLedger;
import com.property.ai.domain.ticket.DomainException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 降级台账 REST（P2-025，契约 v0.3.2 {@code GET /api/v1/degrade/ledger}）。
 *
 * <p>台账口径：一条记录 = 「连接器 × 能力 × 替代路径」的命中聚合（count/firstHitAt/lastHitAt），
 * {@code fallback} 与信封 {@code DegradedInfo.fallback} 同码；{@code from}/{@code to} 按 {@code lastHitAt}
 * 闭区间过滤，分页约定与其他列表一致（page≥1、pageSize≤100）。
 * 只读、不落业务主数据；台账用于降级可解释性（架构 §8）与运营例行回顾。
 */
@RestController
@RequestMapping("/api/v1/degrade")
public class DegradeController {

    private final DegradeLedger degradeLedger;

    public DegradeController(DegradeLedger degradeLedger) {
        this.degradeLedger = degradeLedger;
    }

    @GetMapping("/ledger")
    public ApiResponse<DegradeLedgerResult> ledger(
            @RequestParam(name = "connectorId", required = false) String connectorId,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        ApiSupport.requirePageParams(page, pageSize);
        OffsetDateTime fromTime = parseTime(from, "from");
        OffsetDateTime toTime = parseTime(to, "to");
        if (fromTime != null && toTime != null && fromTime.isAfter(toTime)) {
            throw DomainException.badRequest("from 必须早于（或等于）to",
                    Map.of("field", "from", "from", from, "to", to));
        }
        return ApiResponse.ok(degradeLedger.query(connectorId, fromTime, toTime, page, pageSize), traceId);
    }

    private static OffsetDateTime parseTime(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        OffsetDateTime parsed = ConnectorTimes.parseIsoOrNull(raw);
        if (parsed == null) {
            throw DomainException.badRequest(field + " 必须为 ISO 8601 时间（如 2026-11-15T00:00:00+08:00）",
                    Map.of("field", field, "value", raw));
        }
        return parsed;
    }
}
