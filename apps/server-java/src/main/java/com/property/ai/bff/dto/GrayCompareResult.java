package com.property.ai.bff.dto;

import java.util.List;

/**
 * OpenAPI: GrayCompareResult（灰度双跑比对结果，P5 W19 / v1.3.0）。
 * 字段名 / 必填与 docs/openapi/openapi.yaml v1.3.0 严格对齐；仅 DTO，不含业务逻辑。
 * items 逐能力差异（含 diffRate），generatedAt 生成时间；差异只作发布决策证据，
 * 不得对外声称「新旧连接器等价」。
 */
public record GrayCompareResult(
        List<GrayCompareItem> items,
        String generatedAt) {
}
