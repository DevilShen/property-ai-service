package com.property.ai.bff.api.rag;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 接线配置注册（P6 W30 / task-80）：把 {@link RagProperties}（前缀 {@code property.rag}）纳入 Spring 容器。
 *
 * <p>与 {@code connector/ConnectorConfig}（{@code property.erp}）、{@code domain/channel/ChannelConfig}
 * （{@code property.channel}）同一口径：新增出站依赖只加一组 {@code @ConfigurationProperties}，
 * 不改既有配置类。
 */
@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class RagConfig {
}
