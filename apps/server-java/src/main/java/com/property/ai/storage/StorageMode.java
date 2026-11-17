package com.property.ai.storage;

import java.util.Locale;

/**
 * 存储模式（P5 W21 / P5-017，开关 {@code property.storage.mode}）。
 *
 * <p><b>口径（AGENTS.md P5 门禁）</b>：生产目标 PostgreSQL 16；本环境无 PG 实例、且按约定
 * **不新增 Maven 依赖**（无 JDBC 驱动），故 {@link #POSTGRES} 是「接口就绪 + 可切换开关」，
 * 启动期探测失败即降级 {@link #MEMORY} 并 WARN。**内存模式不代表生产就绪**。
 */
public enum StorageMode {

    /** 进程内内存实现（默认；重启清空，非生产存储） */
    MEMORY("memory"),

    /** PostgreSQL 16（生产目标；本环境无驱动/实例时启动期降级 memory） */
    POSTGRES("postgres");

    private final String value;

    StorageMode(String value) {
        this.value = value;
    }

    /** 配置值 / 日志值（小写，与 {@code property.storage.mode} 取值一致） */
    public String value() {
        return value;
    }

    /**
     * 解析配置值（大小写不敏感）。
     *
     * @return 匹配的模式；不认识的值返回 {@code null}（由调用方 WARN 后按 memory 处理，不因配置笔误阻塞启动）
     */
    public static StorageMode fromValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return MEMORY;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (StorageMode mode : values()) {
            if (mode.value.equals(normalized)) {
                return mode;
            }
        }
        return null;
    }
}
