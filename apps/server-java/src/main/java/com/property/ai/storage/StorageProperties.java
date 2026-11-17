package com.property.ai.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 存储配置（前缀 {@code property.storage}，P5-017）。
 *
 * <p><b>开关语义</b>：
 * <ul>
 *   <li>{@code mode=memory}（默认）：进程内 {@code InMemoryTicketRepository}，重启清空，非生产存储；</li>
 *   <li>{@code mode=postgres}：启用 {@link JdbcTicketRepository}；启动期探测
 *       {@code driver-class} 是否在 classpath、{@code jdbc-url} 是否配置、连接是否可用，
 *       任一失败 → <b>WARN 并降级 memory</b>（不阻断启动，符合「本环境无实例」的交付口径）。</li>
 * </ul>
 *
 * <p><b>依赖边界（AGENTS.md）</b>：本模块只用 JDK 自带 {@code java.sql} + {@code DriverManager}，
 * <b>不引入</b> JDBC 驱动/连接池 Maven 依赖；生产依赖坐标只在
 * {@code docs/ops/PG-迁移方案.md} 文档给出。密码字段仅用于 JDBC 连接，不写日志。
 */
@ConfigurationProperties(prefix = "property.storage")
public class StorageProperties {

    /** 存储模式：{@code memory}（默认）| {@code postgres} */
    private String mode = "memory";

    /**
     * JDBC URL（{@code mode=postgres} 时必填）。
     *
     * <p>示例：{@code jdbc:postgresql://127.0.0.1:5432/property_ai?currentSchema=property_ai}；
     * 默认空串 = 未配置 → 启动期降级 memory（不尝试连库）。
     */
    private String jdbcUrl = "";

    /** 数据库账号（默认空） */
    private String username = "";

    /** 数据库口令（仅 JDBC 使用，不落日志） */
    private String password = "";

    /** JDBC 驱动类（生产坐标 {@code org.postgresql:postgresql}，仅文档给出，不进 pom） */
    private String driverClass = "org.postgresql.Driver";

    /** 建连超时（秒）；探测与真实连接共用 */
    private int connectTimeoutSeconds = 3;

    /**
     * 是否允许 PG 实现执行 {@code clear()}（测试/联调用）。
     *
     * <p>默认 false：生产实现不提供运行时清空（{@code TicketRepository#clear()} 契约注释「生产实现不提供」）。
     */
    private boolean allowReset = false;

    /** 请求的存储模式（非法配置值 → 返回 memory，由调用方 WARN） */
    public StorageMode requestedMode() {
        StorageMode parsed = StorageMode.fromValue(mode);
        return parsed == null ? StorageMode.MEMORY : parsed;
    }

    /** 是否请求了 PostgreSQL */
    public boolean isPostgresRequested() {
        return requestedMode() == StorageMode.POSTGRES;
    }

    /** 配置值是否为合法模式（供装配期 WARN 用） */
    public boolean isModeValid() {
        return StorageMode.fromValue(mode) != null;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDriverClass() {
        return driverClass;
    }

    public void setDriverClass(String driverClass) {
        this.driverClass = driverClass;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public boolean isAllowReset() {
        return allowReset;
    }

    public void setAllowReset(boolean allowReset) {
        this.allowReset = allowReset;
    }
}
