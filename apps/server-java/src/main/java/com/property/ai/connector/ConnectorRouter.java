package com.property.ai.connector;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.property.ai.bff.dto.ConnectorSdk;
import com.property.ai.bff.dto.ErrorCode;
import com.property.ai.connector.spi.ConnectorSpi;
import com.property.ai.domain.ticket.DomainException;

/**
 * 连接器路由器（P5 W19 / task-62，P5-005/011/006/013 的共用基座）。
 *
 * <p>职责：按 {@code connectorId} 把能力调用路由到「已实现 SPI 的连接器实例」，并保留一个
 * <b>默认连接器</b>（P2 起的能力网关主连接器 = 用友 NC mock，{@code @Primary}），使 P2—P4 既有调用方
 * （房档/账单/工单/对账/映射试跑）在单连接器语义下行为完全不变（向后兼容）。
 *
 * <p>注册口径（架构 §24.4「连接器可插拔」）：任何声明为 Spring Bean 的 {@link ConnectorSpi} 都会被自动
 * 收集进路由表（{@link #connectorIds()} 可见）；新增第 3 家 ERP 只需实现 SPI 并声明 Bean，<b>不改本类</b>。
 *
 * <p>边界：路由只决定「调哪家连接器」，<b>不做鉴权/项目隔离</b>（能力矩阵见 {@code PermissionMatrix}，
 * 项目范围见 {@code domain/project/ProjectService}）；未注册连接器 → 404 {@code E-3001}（不新造错误码）。
 */
@Component
public class ConnectorRouter {

    private static final Logger log = LoggerFactory.getLogger(ConnectorRouter.class);

    /** connectorId → 连接器实现（顺序 = Spring Bean 注入顺序，NC 在前） */
    private final Map<String, ErpConnector> byConnectorId = new LinkedHashMap<>();
    /** 默认连接器（{@code @Primary}；单连接器语义的兜底） */
    private final ErpConnector defaultConnector;

    /** Spring 注入构造（P5 W19）：收集全部连接器 Bean + SDK 声明 */
    @Autowired
    public ConnectorRouter(ObjectProvider<ConnectorSpi> spis, ObjectProvider<ErpConnector> connectors) {
        connectors.orderedStream().forEach(connector -> {
            String connectorId = connector.connectorId();
            if (connectorId == null || connectorId.isBlank()) {
                log.warn("[connector] 连接器 {} 未声明 connectorId，跳过路由注册", connector.getClass().getSimpleName());
                return;
            }
            ErpConnector existing = byConnectorId.putIfAbsent(connectorId, connector);
            if (existing != null) {
                log.warn("[connector] connectorId 重复：{}（{} 被忽略）", connectorId,
                        connector.getClass().getSimpleName());
            }
        });
        this.defaultConnector = connectors.getIfAvailable();
        spis.orderedStream().forEach(spi -> log.info("[connector] SDK 已注册：connectorId={} sdkVersion={}",
                spi.connectorId(), spi.sdkVersion()));
    }

    /** 单测/离线场景：仅一个连接器（无 Spring 上下文时构造） */
    public ConnectorRouter(ErpConnector connector) {
        this.defaultConnector = connector;
        if (connector != null && connector.connectorId() != null) {
            this.byConnectorId.put(connector.connectorId(), connector);
        }
    }

    /** 默认连接器（P2—P4 单连接器语义的兜底；可能为 {@code null}，如连接器全部未启用） */
    public ErpConnector defaultConnector() {
        return defaultConnector;
    }

    /** 按 connectorId 查（未注册/无实现返回 empty，便于调用方决定 404/声明口径） */
    public Optional<ErpConnector> find(String connectorId) {
        if (connectorId == null || connectorId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byConnectorId.get(connectorId.trim()));
    }

    /** 按 connectorId 取（未注册 → 404 {@code E-3001}） */
    public ErpConnector require(String connectorId) {
        return find(connectorId).orElseThrow(() -> DomainException.notFound(ErrorCode.E_3001,
                "连接器未接入实现：" + connectorId, Map.of("connectorId", connectorId == null ? "" : connectorId)));
    }

    /**
     * 取连接器实现：{@code connectorId} 为空 → 默认连接器（单连接器语义）；
     * 显式指定但未接入 → 404 {@code E-3001}（不静默回退默认连接器，避免跨连接器串味）。
     */
    public ErpConnector resolve(String connectorId) {
        if (connectorId == null || connectorId.isBlank()) {
            if (defaultConnector == null) {
                throw DomainException.notFound(ErrorCode.E_3001, "无可用连接器实现",
                        Map.of("connectorId", ""));
            }
            return defaultConnector;
        }
        return require(connectorId);
    }

    /** 已注册实现的连接器 ID（顺序固定；用于报告/测试） */
    public List<String> connectorIds() {
        return List.copyOf(byConnectorId.keySet());
    }

    /** 连接器 SDK 声明（仅已实现 SPI 的连接器；未实现返回 empty，由注册表按「注册声明」兜底） */
    public Optional<ConnectorSdk> sdk(String connectorId) {
        return find(connectorId)
                .filter(ConnectorSpi.class::isInstance)
                .map(ConnectorSpi.class::cast)
                .map(ConnectorSpi::sdk);
    }
}
