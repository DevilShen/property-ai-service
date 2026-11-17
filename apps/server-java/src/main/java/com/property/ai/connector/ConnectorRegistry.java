package com.property.ai.connector;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.property.ai.bff.dto.CapabilityCode;
import com.property.ai.bff.dto.CapabilityStatus;
import com.property.ai.bff.dto.ConnectorSdk;
import com.property.ai.bff.dto.ConnectorSdkLimits;
import com.property.ai.bff.dto.ConnectorStatus;
import com.property.ai.connector.spi.ConnectorSpi;

/**
 * 连接器注册表（P2-025，契约 v0.3.2 {@code GET /api/v1/connectors}；P5 W19/W20 多连接器增量）。
 *
 * <p><b>只能声明，不能冒充已验证</b>（契约 README §5）。P5 W20 口径：
 * <b>注册声明 5 家，其中 3 家已接入 mock 测试替身可真实探测</b>：
 * <ul>
 *   <li>{@code yonyou-nc-mock}：已接入 {@code services/mock-erp} 测试替身（:4200），可真实探测，{@code mock: true}；</li>
 *   <li>{@code kingdee-eas}：P5 W19 起接入 {@code services/mock-erp-kingdee} 测试替身（:4400，只读），
 *       可真实探测，{@code mock: true}（<b>仍是测试替身，不接真实金蝶</b>）；</li>
 *   <li>{@code self-built-erp}：P5 W20 起接入第 3 套「自研形态 3.0」测试替身
 *       （数据面复用 :4200，连接器做自有字段 wire 适配，只读），可真实探测，{@code mock: true}
 *       （<b>仍是测试替身，不接真实自研 ERP</b>）；</li>
 *   <li>明源云 / 新视窗：仅注册声明（{@code probeable=false}），能力计数是<b>注册声明</b>，
 *       探测请求返回 503 {@code E-5001}，探测日报不落库。</li>
 * </ul>
 *
 * <h2>可插拔注册（P5-005 / task-62）</h2>
 * 任何声明为 Spring Bean 的 {@link ConnectorSpi} 都会在启动时被 {@link #registerSpi(List)} 自动登记：
 * 若注册表里没有该 {@code connectorId} 则新增一条定义（mock=true / probeable=true），已有则补齐
 * probeable/mock/mappingVersion 与 SDK 声明。第 3 家 ERP 只需实现 SPI + 声明 Bean，
 * <b>不改本类</b>（架构 §24.4）。
 */
@Component
public class ConnectorRegistry {

    private static final Logger log = LoggerFactory.getLogger(ConnectorRegistry.class);

    /** 用友 NC（mock-erp 测试替身）连接器 ID，与契约示例/mock-erp {@code CONNECTOR_ID} 一致 */
    public static final String NC_MOCK = "yonyou-nc-mock";

    /** 金蝶 EAS（mock-erp-kingdee 测试替身 #2，:4400）连接器 ID，与 shared/契约示例一致 */
    public static final String KINGDEE = "kingdee-eas";

    /** 注册声明时间（确定性种子，避免同一响应每次变化） */
    private static final String SEED_BIZ_TIME = "2026-11-15T00:00:00+08:00";

    /** 未实现 SPI 的连接器：仅注册声明，SDK 版本占位 0.0.0（不得当成已接入版本） */
    private static final String DECLARED_SDK_VERSION = "0.0.0";
    /** 未实现 SPI 的连接器：字段字典未登记（不造数） */
    private static final ConnectorSdkLimits DECLARED_LIMITS = new ConnectorSdkLimits(5000, 200, 60);

    private final Map<String, ConnectorDefinition> definitions = new LinkedHashMap<>();
    /** SPI 连接器上报的 SDK 声明（connectorId → ConnectorSdk） */
    private final Map<String, ConnectorSdk> sdkDeclarations = new LinkedHashMap<>();

    public ConnectorRegistry() {
        seed();
    }

    /** 全量注册表（顺序 = 契约示例顺序：用友 NC、金蝶 EAS、明源云、自研 ERP、新视窗） */
    public List<ConnectorDefinition> all() {
        return List.copyOf(definitions.values());
    }

    /** 按 ID 查（未注册返回 empty，不抛异常，便于 BFF 决定错误码） */
    public Optional<ConnectorDefinition> find(String connectorId) {
        if (connectorId == null || connectorId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(definitions.get(connectorId.trim()));
    }

    /**
     * 连接器 SDK 声明（契约 v1.3.0 {@code GET /connectors/{connectorId}/sdk}）。
     *
     * <p>口径：已实现 SPI 的连接器返回其 {@code sdk()}（真实 sdkVersion / 声明能力 / fieldCount / limits）；
     * 仅注册声明（未实现 SPI）的连接器返回<b>注册声明</b> SDK（{@code sdkVersion=0.0.0}、
     * 能力取声明三态里非 unsupported 项、{@code fieldCount=0}），不得把声明说成已验证；
     * 未注册 → {@link Optional#empty()}（BFF 转 404 {@code E-3001}）。
     */
    public Optional<ConnectorSdk> sdkOf(String connectorId) {
        ConnectorDefinition definition = find(connectorId).orElse(null);
        if (definition == null) {
            return Optional.empty();
        }
        ConnectorSdk declared = sdkDeclarations.get(definition.connectorId());
        if (declared != null) {
            return Optional.of(declared);
        }
        List<CapabilityCode> capabilities = new ArrayList<>();
        for (CapabilityCode code : CapabilityCode.values()) {
            if (definition.declaredStates().getOrDefault(code, CapabilityStatus.UNSUPPORTED)
                    != CapabilityStatus.UNSUPPORTED) {
                capabilities.add(code);
            }
        }
        return Optional.of(new ConnectorSdk(DECLARED_SDK_VERSION, definition.connectorId(), List.copyOf(capabilities),
                0, DECLARED_LIMITS));
    }

    /**
     * 可插拔注册（P5-005）：Spring 启动时把全部 {@link ConnectorSpi} Bean 登记进注册表。
     *
     * <p>{@code @Autowired(required = false)} 保持本类无参构造可用（P2—P4 单测/离线场景直接 new）。
     */
    @Autowired(required = false)
    public void registerSpi(List<ConnectorSpi> spis) {
        if (spis == null || spis.isEmpty()) {
            return;
        }
        for (ConnectorSpi spi : spis) {
            String connectorId = spi.connectorId();
            if (connectorId == null || connectorId.isBlank()) {
                log.warn("[connector] SPI {} 未声明 connectorId，跳过注册", spi.getClass().getSimpleName());
                continue;
            }
            ConnectorDefinition existing = definitions.get(connectorId);
            if (existing == null) {
                // 第 3 家及以后：SPI 即注册（mock=true 由连接器实现自行保证测试替身口径）
                definitions.put(connectorId, new ConnectorDefinition(connectorId, shortCode(connectorId),
                        connectorId, spi.vendor(), spi.sdkVersion(), ConnectorStatus.GRAY, true, true, null,
                        ConnectorTimes.nowIso(), declare(CapabilityStatus.SUPPORTED, Map.of())));
                log.info("[connector] SPI 自动注册新连接器：{}", connectorId);
            } else {
                ConnectorDefinition upgraded = new ConnectorDefinition(existing.connectorId(), existing.shortCode(),
                        existing.name(), existing.vendor(), existing.vendorVersion(), existing.status(),
                        true, true, existing.mappingVersion(), existing.updatedAt(), existing.declaredStates());
                definitions.put(connectorId, upgraded);
            }
            sdkDeclarations.put(connectorId, spi.sdk());
            log.info("[connector] SDK 声明已登记：connectorId={} sdkVersion={} capabilities={} fieldCount={}",
                    connectorId, spi.sdkVersion(), spi.sdk().capabilities().size(), spi.fieldCount());
        }
    }

    /** 短码（probeId 前缀 / 台账 id 用）：取 connectorId 首段大写（kingdee-eas → KINGDEE） */
    private static String shortCode(String connectorId) {
        int index = connectorId.indexOf('-');
        String head = index > 0 ? connectorId.substring(0, index) : connectorId;
        return head.toUpperCase();
    }

    private void seed() {
        definitions.put(NC_MOCK, new ConnectorDefinition(NC_MOCK, "NC", "用友 NC Cloud", "yonyou-nc",
                "NC Cloud V6.5.3", ConnectorStatus.PUBLISHED, true, true, "v1.8.2", SEED_BIZ_TIME,
                declare(CapabilityStatus.SUPPORTED, Map.of())));
        // P5 W19：金蝶替身（:4400，只读）已接入 → mock=true / probeable=true；能力声明 14/3/1 与替身一致
        definitions.put(KINGDEE, new ConnectorDefinition(KINGDEE, "KD", "金蝶 EAS", "kingdee",
                "EAS V8.2 SP3", ConnectorStatus.GRAY, true, true, "v2.1.0", SEED_BIZ_TIME,
                declare(CapabilityStatus.SUPPORTED, Map.of(
                        CapabilityCode.INVOICE_APPLY, CapabilityStatus.DEGRADED,
                        CapabilityCode.TICKET_ASSIGN, CapabilityStatus.DEGRADED,
                        CapabilityCode.NOTICE_PUSH, CapabilityStatus.DEGRADED,
                        CapabilityCode.PARKING_AUTH, CapabilityStatus.UNSUPPORTED))));
        definitions.put("mingyuan-cloud", new ConnectorDefinition("mingyuan-cloud", "MY", "明源云 · 云物业", "mingyuan",
                "云物业 V6.0", ConnectorStatus.DRAFT, false, false, null, SEED_BIZ_TIME,
                declare(CapabilityStatus.SUPPORTED, Map.of(
                        CapabilityCode.DUNNING_TASK, CapabilityStatus.DEGRADED,
                        CapabilityCode.NOTICE_RECEIPT, CapabilityStatus.DEGRADED,
                        CapabilityCode.VISIT_RECORD, CapabilityStatus.DEGRADED,
                        CapabilityCode.PAY_JUMP, CapabilityStatus.UNSUPPORTED,
                        CapabilityCode.INVOICE_APPLY, CapabilityStatus.UNSUPPORTED,
                        CapabilityCode.PARKING_AUTH, CapabilityStatus.UNSUPPORTED))));
        // P5 W20（task-65 / P5-012）：第 3 套连接器「自研形态」（self-built-erp）已接入 mock 测试替身
        // （数据面复用 :4200 NC mock，connector/selfbuilt 做自有形态 wire 适配）→ mock=true / probeable=true；
        // 能力声明 12/4/2 与 SelfBuiltCapabilityProfile 画像一致（degraded: ticket.assign/invoice.apply/
        // visit.record/dunning.task；unsupported: pay.jump/parking.auth）。
        definitions.put("self-built-erp", new ConnectorDefinition("self-built-erp", "SELF", "自研 ERP · property-erp 3.0",
                "self-built", "自有形态 3.0（mock 替身）", ConnectorStatus.GRAY, true, true, "v3.0.0", SEED_BIZ_TIME,
                declare(CapabilityStatus.SUPPORTED, Map.of(
                        CapabilityCode.TICKET_ASSIGN, CapabilityStatus.DEGRADED,
                        CapabilityCode.INVOICE_APPLY, CapabilityStatus.DEGRADED,
                        CapabilityCode.VISIT_RECORD, CapabilityStatus.DEGRADED,
                        CapabilityCode.DUNNING_TASK, CapabilityStatus.DEGRADED,
                        CapabilityCode.PAY_JUMP, CapabilityStatus.UNSUPPORTED,
                        CapabilityCode.PARKING_AUTH, CapabilityStatus.UNSUPPORTED))));
        definitions.put("xinshichuang", new ConnectorDefinition("xinshichuang", "XSC", "新视窗", "newview",
                "V5.2", ConnectorStatus.DRAFT, false, false, null, SEED_BIZ_TIME,
                declare(CapabilityStatus.UNSUPPORTED, Map.of())));
    }

    /** 声明构造：默认三态 + 覆写项（顺序固定 CAP-01…CAP-18） */
    private static Map<CapabilityCode, CapabilityStatus> declare(CapabilityStatus defaultState,
            Map<CapabilityCode, CapabilityStatus> overrides) {
        Map<CapabilityCode, CapabilityStatus> states = new EnumMap<>(CapabilityCode.class);
        for (CapabilityCode code : CapabilityCode.values()) {
            states.put(code, overrides.getOrDefault(code, defaultState));
        }
        return states;
    }
}
