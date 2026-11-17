package com.property.ai.domain.channel;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通道侧脱敏工具（P6-005 / P6-007 脱敏复核）。
 *
 * <p>契约与 AGENTS.md NEVER #8 要求仓库/日志/通道请求都<b>不得出现完整业主手机号</b>。
 * 上游本应只传掩码，但适配层做一次「保险丝」：即便调用方误传 11 位手机号，
 * 也会在落回执/日志前被重新掩码（{@code 138****8000}），避免真实号码扩散。
 *
 * <p>本类只做文本掩码，不做加解密/不接触密钥；纯函数，无外部依赖。
 */
public final class ChannelMasking {

    /** 中国大陆手机号（11 位，1 开头，第二位 3—9）；用于「误传即掩码」的兜底 */
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(1[3-9]\\d)(\\d{4})(\\d{4})(?!\\d)");

    /** 邮箱（通道回执/日志同样不得出现完整邮箱） */
    private static final Pattern EMAIL = Pattern.compile("([A-Za-z0-9._%+-]{1,3})[A-Za-z0-9._%+-]*(@[A-Za-z0-9.-]+)");

    private ChannelMasking() {
    }

    /**
     * 手机号掩码：{@code 13800138000 → 138****8000}。
     *
     * <p>已经是掩码形态（长度不足/含 {@code *}）或空值原样返回；{@code null} 返回空串。
     */
    public static String maskPhone(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = raw.trim();
        Matcher matcher = PHONE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, matcher.group(1) + "****" + matcher.group(3));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** 邮箱掩码：{@code zhangsan@example.com → zha***@example.com}；空值/null → 空串 */
    public static String maskEmail(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        // replaceAll 保留前后文（不能只取匹配段，否则会把文本前半段丢掉）
        return EMAIL.matcher(raw.trim()).replaceAll("$1***$2");
    }

    /**
     * 通用文本掩码（用于降级原因/日志兜底）：手机号与邮箱都会被掩码。
     *
     * <p>只用于人类可读文本；<b>不得</b>把掩码结果当作可回传的原始数据。
     */
    public static String maskText(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return maskEmail(maskPhone(raw));
    }
}
