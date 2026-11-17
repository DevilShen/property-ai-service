package com.property.ai.bff.dto;

import java.util.List;

/**
 * OpenAPI: DunningScript（P3 W10 / v1.1.0 增量）。
 * 催缴话术（P3 W10 增量）。生成不等于外发：话术必须先过禁区扫描（forbiddenHits 为空才可发送）
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.1.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record DunningScript(
        /** 话术正文（已脱敏，不含完整手机号/身份证；发送前需人工确认） */
        String content,
        /** 命中的禁区规则 code 列表（空数组 = 未命中，可发送；非空 = 不得发送） */
        List<String> forbiddenHits,
        /** 话术版本（如 v1）；send 必须回传该版本 */
        String version,
        DunningTone tone,
        DunningChannel channel) {
}
