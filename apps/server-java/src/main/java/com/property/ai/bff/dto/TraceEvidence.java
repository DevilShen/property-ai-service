package com.property.ai.bff.dto;

import java.util.List;

/**
 * OpenAPI: TraceEvidence（P4 W15 / v1.2.0 增量）。
 * 证据包（P4 v1.2.0，JSON 形态）。**导出前必须脱敏**：`maskedFields` 列出已脱敏字段，
 * `checksum` 为内容校验和（SHA-256 前 16 位，便于举证一致性）。
 * 同端点 `format=csv` 时 2xx 为 `text/csv` 文件流（**非 JSON 信封**，契约例外见 README §6.2）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record TraceEvidence(
        /** 链路 ID */
        String traceId,
        /** 生成时间 */
        String generatedAt,
        /** 内容校验和（SHA-256 前 16 位） */
        String checksum,
        /** 证据节点（与时间线一致） */
        List<TraceEntry> entries,
        /** 已脱敏字段清单 */
        List<String> maskedFields,
        /** 演示标记 */
        boolean mock) {
}
