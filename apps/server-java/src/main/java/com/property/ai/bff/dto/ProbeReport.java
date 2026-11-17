package com.property.ai.bff.dto;

import java.util.List;

/**
 * OpenAPI: ProbeReport（能力探测日报，v0.3.2）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.2 严格对齐；仅 DTO，不含业务逻辑。
 */
public record ProbeReport(
        String probeId,
        String connectorId,
        String startedAt,
        String finishedAt,
        /** 18 项能力探测态（顺序 CAP-01…CAP-18） */
        List<CapabilityState> states,
        ProbeSummary summary) {
}
