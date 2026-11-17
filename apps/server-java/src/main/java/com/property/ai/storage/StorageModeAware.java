package com.property.ai.storage;

/**
 * 存储模式自描述接口（P5-017）。
 *
 * <p>既有仓储接口（{@code TicketRepository} 等）保持**形状不变**（不改契约/不改 domain 接口），
 * 需要暴露「请求模式 / 生效模式 / 降级原因」的实现额外实现本接口，供启动报告、运维排查与测试断言使用。
 */
public interface StorageModeAware {

    /** 当前存储生效状态（请求值 / 生效值 / 降级原因） */
    StorageStatus storageStatus();
}
