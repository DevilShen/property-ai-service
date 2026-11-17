package com.property.ai.domain.channel;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 通道适配层装配（P6 W25 / P6-005）。
 *
 * <p>装配三个领域接口 Bean（{@link SmsChannel} / {@link PaymentChannel} / {@link InvoiceChannel}），
 * 默认实现均为 {@link MockChannelAdapter}（占位）。<b>不新增任何 Controller/对外端点</b>，
 * 因此契约 v1.3.1（80 path / 84 operation / 289 schema）不变；后续真实接入时只需替换 Bean 实现，
 * 对上层调用方零改动。
 *
 * <p><b>降级口径</b>：{@code property.channel.<sms|payment|invoice>.mode} 取
 * {@code disabled|mock|real}；{@code real} 时本仓库无真实实现 → 启动 WARN + 受理返回 503 占位
 * （见 {@link MockChannelAdapter}）。默认全部 {@code mock}，与 P5 存储/缓存/事件「三模式降级」同口径：
 * <b>不阻断启动、不假装真实通道就绪、不伪造成功</b>。
 */
@Configuration
@EnableConfigurationProperties(ChannelProperties.class)
public class ChannelConfig {

    /**
     * 占位适配器（同时实现三个通道接口；启动期打印模式口径与 real/disabled WARN）。
     *
     * <p>{@code @Primary}：三个接口 Bean 与它指向同一实例，标记 primary 后
     * 「按接口注入」与「按具体类型注入」都能唯一解析（未来替换真实实现时去掉 primary 即可）。
     */
    @Bean
    @Primary
    public MockChannelAdapter mockChannelAdapter(ChannelProperties channelProperties) {
        return new MockChannelAdapter(channelProperties);
    }

    /** 短信通道（默认占位实现；真实接入时替换为 RealSmsChannel） */
    @Bean
    public SmsChannel smsChannel(MockChannelAdapter mockChannelAdapter) {
        return mockChannelAdapter;
    }

    /** 支付通道（默认占位实现；不扣款、不伪造支付成功） */
    @Bean
    public PaymentChannel paymentChannel(MockChannelAdapter mockChannelAdapter) {
        return mockChannelAdapter;
    }

    /** 发票通道（默认占位实现；不开票、不伪造发票号） */
    @Bean
    public InvoiceChannel invoiceChannel(MockChannelAdapter mockChannelAdapter) {
        return mockChannelAdapter;
    }
}
