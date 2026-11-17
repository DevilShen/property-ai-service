package com.property.ai.connector;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 连接器装配：注册不依赖具体厂商的实现（{@link ErpConnector} 由 {@code connector.nc} / {@code connector.kingdee}
 * 提供），并按连接器维度实例化出站客户端与熔断器。
 *
 * <p>P5 W19 多连接器（task-62 / P5-005）：第 1 套用友 NC 沿用 {@link HttpErpClient} 默认 Bean（{@code @Primary}，
 * 基地址 {@code property.erp.base-url}）；第 2 套金蝶由 {@link #kingdeeHttpErpClient} 提供<b>独立实例</b>
 * （基地址 {@code property.erp.kingdee-base-url} + 独立熔断器），避免两个 ERP 的超时/熔断/限流互相污染。
 * P5 W20（task-65 / P5-012）第 3 套自研连接器按同一口径增加 {@link #selfBuiltHttpErpClient}
 * （基地址 {@code property.erp.self-built-base-url} + 独立熔断器）。
 * 新增第 4 家 ERP 只需新增一个同形 Bean + 实现 {@link com.property.ai.connector.spi.ConnectorSpi}，
 * <b>不改本类既有装配</b>。
 */
@Configuration
@EnableConfigurationProperties({ConnectorProperties.class, ChannelProperties.class})
public class ConnectorConfig {

    /** 默认连接器（NC）熔断器：{@code @Primary} 保持既有单连接器注入语义不变 */
    @Bean
    @Primary
    public CircuitBreaker erpCircuitBreaker(ConnectorProperties properties) {
        ConnectorProperties.CircuitBreakerSettings settings = properties.getCircuitBreaker();
        return new CircuitBreaker(settings.isEnabled(), settings.getFailureThreshold(), settings.getCooldownMs());
    }

    /** 金蝶连接器熔断器（按连接器维度隔离；阈值/冷却沿用统一配置） */
    @Bean(name = "kingdeeCircuitBreaker")
    public CircuitBreaker kingdeeCircuitBreaker(ConnectorProperties properties) {
        ConnectorProperties.CircuitBreakerSettings settings = properties.getCircuitBreaker();
        return new CircuitBreaker(settings.isEnabled(), settings.getFailureThreshold(), settings.getCooldownMs());
    }

    /**
     * 金蝶连接器出站客户端（P5-011）：同一套超时/重试/熔断/错误码翻译语义，只覆盖 base-url 与单次预算。
     *
     * <p>Bean 名固定 {@code kingdeeHttpErpClient}（{@code KingdeeErpConnector} 用 {@code @Qualifier} 注入），
     * 不复用默认客户端避免多 ERP 串味。
     */
    @Bean(name = "kingdeeHttpErpClient")
    public HttpErpClient kingdeeHttpErpClient(ObjectMapper objectMapper, ConnectorProperties properties,
            @Qualifier("kingdeeCircuitBreaker") CircuitBreaker kingdeeCircuitBreaker,
            ConnectorTelemetry telemetry) {
        return new HttpErpClient(objectMapper, properties, kingdeeCircuitBreaker, telemetry,
                properties.getKingdeeBaseUrl(), properties.getKingdeeTimeoutMs());
    }

    /**
     * 自研连接器熔断器（P5-012）：按连接器维度隔离，阈值/冷却沿用统一配置。
     *
     * <p>第 3 套连接器（{@code self-built-erp}）与 NC/金蝶互不污染：自研形态超时预算更小（1200ms），
     * 若共用一个熔断器，自研数据面抖动会把 NC 主链路一起熔断。
     */
    @Bean(name = "selfBuiltCircuitBreaker")
    public CircuitBreaker selfBuiltCircuitBreaker(ConnectorProperties properties) {
        ConnectorProperties.CircuitBreakerSettings settings = properties.getCircuitBreaker();
        return new CircuitBreaker(settings.isEnabled(), settings.getFailureThreshold(), settings.getCooldownMs());
    }

    /**
     * 自研连接器出站客户端（P5-012 / task-65）：同一套超时/重试/熔断/错误码翻译语义，
     * 只覆盖 base-url（{@code property.erp.self-built-base-url}，默认复用 NC mock 数据面 :4200）与单次预算。
     *
     * <p>Bean 名固定 {@code selfBuiltHttpErpClient}，不复用默认客户端避免多 ERP 串味。
     */
    @Bean(name = "selfBuiltHttpErpClient")
    public HttpErpClient selfBuiltHttpErpClient(ObjectMapper objectMapper, ConnectorProperties properties,
            @Qualifier("selfBuiltCircuitBreaker") CircuitBreaker selfBuiltCircuitBreaker,
            ConnectorTelemetry telemetry) {
        return new HttpErpClient(objectMapper, properties, selfBuiltCircuitBreaker, telemetry,
                properties.getSelfBuiltBaseUrl(), properties.getSelfBuiltTimeoutMs());
    }
}
