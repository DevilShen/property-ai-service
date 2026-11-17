package com.property.ai.domain.channel;

/**
 * 外部通道模式（P6 W25 / P6-005，配置前缀 {@code property.channel.<sms|payment|invoice>.mode}）。
 *
 * <p><b>三态语义</b>：
 * <ul>
 *   <li>{@link #DISABLED}：通道关闭，受理直接返回 503 占位（{@code status=disabled}）；</li>
 *   <li>{@link #MOCK}（默认）：占位受理，返回 {@code status=mocked}，<b>不产生任何真实发送/扣款/开票</b>；</li>
 *   <li>{@link #REAL}：请求真实通道。本仓库<b>尚未接入任何真实通道实现</b>（AGENTS.md NEVER #4：
 *       不接真实支付/发票/短信），因此 {@code real} 一律降级为 503 占位并 WARN；
 *       有凭据时原因记为「实现未接入」，无凭据时原因列出缺失的配置键名（<b>只给键名，不给值</b>）。</li>
 * </ul>
 *
 * <p>未知配置值按 {@link #MOCK} 处理并标记非法（{@link ChannelProperties#isModeValid(ChannelKind)}），
 * 装配期由 {@link MockChannelAdapter} 打一次性 WARN —— 与 P5 存储/缓存/事件的降级口径一致：
 * <b>不阻断启动，但绝不假装真实通道已就绪</b>。
 */
public enum ChannelMode {

    /** 通道关闭（受理返回 503 占位） */
    DISABLED("disabled"),

    /** 占位/mock（默认；返回 status=mocked，不真实外发） */
    MOCK("mock"),

    /** 请求真实通道（当前无真实实现 → 503 占位 + WARN） */
    REAL("real");

    private final String value;

    ChannelMode(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** 解析配置值（忽略大小写/空白）；非法或空值返回 {@code null}（调用方决定降级口径） */
    public static ChannelMode fromValue(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(java.util.Locale.ROOT);
        for (ChannelMode mode : values()) {
            if (mode.value.equals(normalized)) {
                return mode;
            }
        }
        return null;
    }
}
