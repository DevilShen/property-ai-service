package com.property.ai.domain.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 通道模式启动报告（P6 W25 / P6-005），格式与 P5 的 {@code StorageStartupReporter} 保持一致。
 *
 * <p>启动完成后打一行统一口径日志，便于运维/验收快速判断三个通道当前生效模式：
 * <pre>
 * [P6-W25] channelMode(sms=mock/credentialConfigured=false payment=mock/... invoice=mock/...) degraded=false placeholderOnly=true
 * </pre>
 * 任一通道请求 {@code real}（真实通道未接入）或 {@code disabled}/配置非法时，再打一条汇总 WARN。
 *
 * <p><b>安全</b>：日志只含模式与「凭据已配置/未配置」布尔，<b>不含任何凭据值</b>（P6-007 密钥管理红线）。
 *
 * <p><b>口径</b>：本仓库默认三通道均为 {@code mock} 占位（不发送短信/不扣款/不开票），
 * 这是「适配层就绪 + 占位降级」，<b>不代表真实通道已接入</b>（AGENTS.md NEVER #4）。
 */
@Component
public class ChannelStartupReporter {

    private static final Logger log = LoggerFactory.getLogger(ChannelStartupReporter.class);

    private final MockChannelAdapter channelAdapter;

    public ChannelStartupReporter(MockChannelAdapter channelAdapter) {
        this.channelAdapter = channelAdapter;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        boolean degraded = channelAdapter.degraded();
        log.info("[P6-W25] {} degraded={} placeholderOnly=true", channelAdapter.summary(), degraded);
        if (degraded) {
            log.warn("[P6-W25] WARN 通道非 mock 模式（real/disabled/非法配置）：{}；real 通道在本仓库未接入，"
                    + "受理一律 503 占位（不真实发送/扣款/开票，不伪造成功）；接入前置见 "
                    + "docs/prod/P6-通道配置模板.md", channelAdapter.summary());
        }
    }
}
