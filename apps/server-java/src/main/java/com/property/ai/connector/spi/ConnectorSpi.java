package com.property.ai.connector.spi;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.property.ai.bff.dto.CapabilityCode;
import com.property.ai.bff.dto.ConnectorSdk;
import com.property.ai.bff.dto.ConnectorSdkLimits;
import com.property.ai.connector.Capability;
import com.property.ai.connector.ErpConnector;

/**
 * 连接器 SDK 抽象（P5-005 / task-62，契约 v1.3.0 {@code ConnectorSdk} / {@code GET /connectors/{id}/sdk}，
 * 架构 §24.4「连接器可插拔」）。
 *
 * <p><b>可插拔口径</b>：第 2/3 家 ERP 只要「实现本接口 + 声明为 Spring Bean」即可注册进
 * {@link com.property.ai.connector.ConnectorRegistry} 并被能力网关路由/探测，<b>不改 BFF 与对外契约</b>：
 * <ol>
 *   <li>{@link #connectorId()} 注册表主键（如 {@code yonyou-nc-mock} / {@code kingdee-eas}）；</li>
 *   <li>{@link #capabilities()} 实现的标准能力集合（{@link Capability}，决定能力网关可调用面与重试口径）；</li>
 *   <li>{@link #sdk()} SDK 声明：{@code sdkVersion} / 声明能力（⊆18 项 {@link CapabilityCode}）/
 *       {@code fieldCount}（字段字典条目数）/ {@code limits{timeoutMs,pageSizeMax,rateLimitPerMin}}；</li>
 *   <li>读方法契约继承自 {@link ErpConnector}（room/bill/ticket/owner 只读能力，写能力按连接器是否支持实现）。</li>
 * </ol>
 *
 * <p><b>声明先于探测、探测覆盖声明</b>：{@code sdk.capabilities} 是连接器<b>声明可实现</b>的标准能力，
 * 能力是否真的可用以 {@code POST /connectors/{id}/probe} 的 18 项三态为准，不得据声明对外承诺
 * （契约 README §1.6 / §7.6）。
 *
 * <p><b>mock 边界</b>：本仓库内所有连接器都指向测试替身（{@code services/mock-erp*}），不接真实 ERP、
 * 不持有真实凭据，{@code ConnectorDefinition.mock=true} 必须在注册表体现（AGENTS.md NEVER #4/#11）。
 */
public interface ConnectorSpi extends ErpConnector {

    /** 连接器 ID（注册表主键，契约 {@code ConnectorSummary.connectorId}） */
    @Override
    String connectorId();

    /** SDK 语义化版本（契约 {@code ConnectorSdk.sdkVersion}） */
    String sdkVersion();

    /** 字段字典条目数（契约 {@code ConnectorSdk.fieldCount}；连接器声明，不依赖 ERP 在线） */
    int fieldCount();

    /** 运行限制（契约 {@code ConnectorSdkLimits}；由连接器运行时强制） */
    ConnectorSdkLimits sdkLimits();

    /**
     * 声明的标准能力码（⊆ 18 项 {@link CapabilityCode}，固定 {@code CapabilityCode} 枚举顺序，
     * 由 {@link #capabilities()} 的契约码去重派生）。
     */
    default Set<CapabilityCode> declaredCapabilities() {
        Set<CapabilityCode> declared = new LinkedHashSet<>();
        for (CapabilityCode code : CapabilityCode.values()) {
            for (Capability capability : capabilities()) {
                if (capability.contractCode() == code) {
                    declared.add(code);
                }
            }
        }
        // 必须保留 LinkedHashSet 的插入顺序（= 18 项 CapabilityCode 枚举顺序）：
        // Set.copyOf(...) 是无序不可变集合（迭代序按 JVM 哈希盐随机），会让 SDK 声明的 capabilities[]
        // 每次启动顺序不同（契约要求顺序固定，`GET /connectors/{id}/sdk` 的 capabilities[0] 稳定为 room.query）。
        return Collections.unmodifiableSet(declared);
    }

    /** SDK 声明（契约 {@code ConnectorSdk}；能力顺序固定 = 18 项能力码枚举顺序的子集）。 */
    default ConnectorSdk sdk() {
        return new ConnectorSdk(sdkVersion(), connectorId(), List.copyOf(declaredCapabilities()), fieldCount(),
                sdkLimits());
    }
}
