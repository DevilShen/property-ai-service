package com.property.ai.bff.dto;

import java.util.List;

/**
 * OpenAPI: ProbeLatestResult（最近一次探测 + 近 7 日历史，v0.3.2）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.2 严格对齐；仅 DTO，不含业务逻辑。
 */
public record ProbeLatestResult(
        /** 最近一次完整探测（无则 null） */
        ProbeReport latest,
        /** 近 7 日探测日报（按 startedAt 升序） */
        List<ProbeReport> history) {
}
