package com.property.ai.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.property.ai.domain.ticket.InMemoryTicketRepository;
import com.property.ai.domain.ticket.TicketRepository;

/**
 * 存储装配（P5-017）。
 *
 * <p>既有仓储接口（{@link TicketRepository}）与内存实现（{@link InMemoryTicketRepository}）形状不变：
 * 本类注册一个 {@code @Primary} 的 {@link TicketRepository} = {@link JdbcTicketRepository}，它内部
 * 「PG 可用走 PG / 否则委托内存实现」，因此：
 * <ul>
 *   <li>默认 {@code property.storage.mode=memory}：行为与 P5 W20 完全一致（纯内存，服务层零改动）；</li>
 *   <li>{@code mode=postgres} 且探测通过：工单平台侧数据落 PG（{@code docs/ops/PG-schema.sql}）；</li>
 *   <li>{@code mode=postgres} 但无驱动/无实例/连接失败：<b>启动期 WARN + 降级内存</b>，服务照常起。</li>
 * </ul>
 *
 * <p>运行期不经 {@code /healthz} 暴露存储模式（不改既有端点形状），启动日志与
 * {@link StorageStartupReporter} 输出 {@code storageMode=...}；测试/运维可通过 Bean 类型断言。
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StorageConfig.class);

    /**
     * 工单仓储（{@code @Primary}）：PG 可用即走 PG，否则透明委托内存实现。
     *
     * <p>{@code initialize()} 在装配期执行一次（启动期探测 + 降级 WARN），保证探针语义确定。
     *
     * <p>兼容短开关 {@code property.storage=memory|postgres}（与 {@code property.storage.mode} 等价，
     * 任务卡/运维命令常用短形式）；两者同时存在时以 {@code property.storage.mode} 为准。
     */
    @Bean
    @Primary
    public TicketRepository ticketRepository(InMemoryTicketRepository memoryTicketRepository,
            StorageProperties properties, ObjectMapper objectMapper, Environment environment) {
        applyShortFormMode(properties, environment);
        JdbcTicketRepository repository = new JdbcTicketRepository(properties, memoryTicketRepository, objectMapper);
        repository.initialize();
        return repository;
    }

    /** 短开关兼容：{@code property.storage} → {@code property.storage.mode}（显式长开关优先） */
    static void applyShortFormMode(StorageProperties properties, Environment environment) {
        if (environment == null) {
            return;
        }
        String explicitMode = environment.getProperty("property.storage.mode");
        if (explicitMode != null && !explicitMode.isBlank()) {
            return;
        }
        String shortForm = environment.getProperty("property.storage");
        if (shortForm != null && !shortForm.isBlank()) {
            properties.setMode(shortForm.trim());
            log.info("[storage] 使用短开关 property.storage={}（等价 property.storage.mode={}）", shortForm.trim(),
                    shortForm.trim());
        }
    }
}
