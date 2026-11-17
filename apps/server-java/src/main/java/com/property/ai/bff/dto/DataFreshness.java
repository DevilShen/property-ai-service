package com.property.ai.bff.dto;

/**
 * OpenAPI: DataFreshness（只读数据新鲜度（命中缓存/降级 fallback=cache 时必带），v0.3.2）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.2 严格对齐；仅 DTO，不含业务逻辑。
 */
public record DataFreshness(
        /** 数据来源（erp / mock / cache） */
        String source,
        /** 数据时点 */
        String dataAsOf,
        /** 展示缓存 TTL（秒）；0 = 非缓存直读 */
        int ttlSeconds,
        /** 是否命中缓存 */
        boolean hit) {
}
