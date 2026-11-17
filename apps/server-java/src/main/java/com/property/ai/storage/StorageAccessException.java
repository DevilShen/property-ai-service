package com.property.ai.storage;

/**
 * 存储访问异常（P5-017）。
 *
 * <p>语义：<b>启动期探测通过后</b>运行期 SQL 失败才抛本异常（连接中断/约束冲突/SQL 错误等）。
 * 启动期失败不抛异常，走「WARN + 降级 memory」，避免本环境（无 PG）起不来。
 *
 * <p>不做运行期自动降级回内存：写路径半途切内存会造成 PG/内存数据分叉，正确做法是
 * 运维按 {@code docs/ops/PG-迁移方案.md} §7 回滚开关。异常信息<b>不含</b>口令/完整连接串。
 */
public class StorageAccessException extends RuntimeException {

    public StorageAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
