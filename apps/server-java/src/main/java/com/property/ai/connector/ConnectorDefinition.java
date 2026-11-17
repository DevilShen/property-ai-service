package com.property.ai.connector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.property.ai.bff.dto.CapabilityCode;
import com.property.ai.bff.dto.CapabilityState;
import com.property.ai.bff.dto.CapabilityStatus;
import com.property.ai.bff.dto.ConnectorStatus;

/**
 * 连接器注册声明（契约 v0.3.2 {@code ConnectorSummary} 的静态半边）。
 *
 * <p>口径（架构 §4.1/§4.3）：注册表登记 5 套 ERP 的生命周期状态（draft/gray/published/retired）、
 * 厂商标识与<b>声明能力</b>；「声明先于探测、探测覆盖声明」——有探测日报时能力计数以探测结果为准，
 * 未接入/未探测的连接器只返回声明（{@code evidence} 明确标注「注册声明（未探测）」，
 * 不得把声明说成已验证）。
 */
public record ConnectorDefinition(
        String connectorId,
        /** 短码（probeId / 台账 id 用，如 NC） */
        String shortCode,
        String name,
        String vendor,
        String vendorVersion,
        ConnectorStatus status,
        /** 是否 mock-erp 测试替身（P2 W7 仅用友 NC 为 true） */
        boolean mock,
        /** 是否可真实探测（仅已接入且在线的连接器） */
        boolean probeable,
        /** 当前生效映射版本号（未发布为 null） */
        String mappingVersion,
        String updatedAt,
        Map<CapabilityCode, CapabilityStatus> declaredStates) {

    public ConnectorDefinition {
        declaredStates = declaredStates == null ? Map.of() : Map.copyOf(declaredStates);
    }

    /** 声明里某三态的能力数 */
    public int declaredCount(CapabilityStatus state) {
        return (int) declaredStates.values().stream().filter(value -> value == state).count();
    }

    /** 未探测时的 18 项能力声明行（顺序 CAP-01…CAP-18；checkedAt 用注册更新时间） */
    public List<CapabilityState> declaredCapabilityStates() {
        List<CapabilityState> rows = new ArrayList<>();
        for (CapabilityCatalog.Entry entry : CapabilityCatalog.entries()) {
            CapabilityStatus state = declaredStates.getOrDefault(entry.code(), CapabilityStatus.UNSUPPORTED);
            rows.add(new CapabilityState(entry.designCode(), entry.code(), entry.name(), entry.level(), state,
                    updatedAt, "注册声明（未探测；连接器 status=" + status.value() + "）", entry.fallback()));
        }
        return List.copyOf(rows);
    }
}
