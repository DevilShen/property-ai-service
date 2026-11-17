package com.property.ai.connector;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.property.ai.bff.dto.BillInfo;
import com.property.ai.bff.dto.BillListResult;
import com.property.ai.bff.dto.BillListSort;
import com.property.ai.bff.dto.BillStatus;
import com.property.ai.bff.dto.BindState;
import com.property.ai.bff.dto.CapabilityState;
import com.property.ai.bff.dto.MappingField;
import com.property.ai.bff.dto.OwnerVerifyRequest;
import com.property.ai.bff.dto.OwnerVerifyResult;
import com.property.ai.bff.dto.RoomInfo;
import com.property.ai.bff.dto.RoomListResult;
import com.property.ai.bff.dto.RoomListSort;
import com.property.ai.bff.dto.Ticket;
import com.property.ai.bff.dto.TicketCreateRequest;
import com.property.ai.bff.dto.TicketFeedbackRequest;
import com.property.ai.bff.dto.TicketListItem;
import com.property.ai.bff.dto.TicketListResult;
import com.property.ai.bff.dto.TicketStatus;
import com.property.ai.bff.dto.TicketTransitionAction;

/**
 * ERP 连接器能力接口（能力网关是 AI 侧访问 ERP 的**唯一入口**，设计 §24.4）。
 *
 * <p>约定：
 * <ul>
 *   <li>只暴露标准能力，不暴露厂商私有协议；返回值一律是契约 DTO（{@code bff/dto/*}），
 *       字段与 {@code docs/openapi/openapi.yaml} v0.3.0 对齐；</li>
 *   <li>能力声明（{@link #capabilities()}）驱动前端入口显隐与降级（缺能力 → E-5001 + 替代通道）；</li>
 *   <li>所有方法带 {@code traceId} 并透传 {@code X-Trace-Id}，便于端到端回溯；</li>
 *   <li>失败统一抛 {@link ConnectorException}（携带 17 类错误码），不返回 null 冒充成功。</li>
 * </ul>
 *
 * <p>已声明能力（P2 第 1 周 8 项；18 项全集见设计 §3.2，其余 P3/P5 补）：
 * room.query / owner.verify / bill.query / ticket.create / ticket.status / ticket.list /
 * ticket.transition / visit.record（回访）。
 */
public interface ErpConnector {

    /** 连接器/厂商标识（写进 ErpRef.vendor；P2 第 1 周为 yonyou-nc-mock） */
    String vendor();

    /**
     * 连接器 ID（注册表主键，契约 {@code ConnectorSummary.connectorId}）。
     *
     * <p>P5 W19 多连接器增量：默认回退 {@link #vendor()} 以保持 P2—P4 既有实现（含测试桩）无感；
     * 多连接器实现（{@code ConnectorSpi}）应显式覆盖（如 {@code kingdee-eas}）。
     */
    default String connectorId() {
        return vendor();
    }

    /** 声明的能力集合 */
    Set<Capability> capabilities();

    /** 是否支持某能力（缺能力时 BFF 返回 E-5001 并隐藏入口） */
    default boolean supports(Capability capability) {
        return capabilities().contains(capability);
    }

    /** 健康状态（来自连接器运行时的最近一次探测） */
    ConnectorHealth health();

    /** CAP-02 owner.verify：手机号后四位核验（P2 演示因子） */
    OwnerVerifyResult verifyOwner(OwnerVerifyRequest request, String traceId);

    /** CAP-01 room.query：房档只读 */
    RoomInfo queryRoom(String roomCode, String traceId);

    /** CAP-03 bill.query：账单只读（按房号 + 账期） */
    BillInfo queryBill(String roomCode, String period, String traceId);

    /** CAP-06 ticket.create：建单（写操作，必须携带幂等键；不自动重试） */
    Ticket createTicket(TicketCreateRequest request, String idempotencyKey, String traceId);

    /** CAP-08 ticket.status：工单详情 */
    Ticket getTicket(String ticketNo, String traceId);

    /** CAP-08 ticket.list：工单列表（分页 + 状态/班组筛选） */
    TicketListResult listTickets(TicketStatus status, String team, int page, int pageSize, String traceId);

    /** CAP-07/09/13 ticket 动作：状态迁移（写操作，不自动重试；幂等键原样透传给 ERP 便于跨端去重） */
    Ticket transitionTicket(String ticketNo, TicketTransitionAction action, Map<String, Object> payload,
            String idempotencyKey, String traceId);

    /** CAP-13 visit.record：回访评价（写操作，不自动重试） */
    Ticket submitFeedback(String ticketNo, TicketFeedbackRequest request, String idempotencyKey, String traceId);

    // ------------------------------------------------------------------
    // v0.3.2 增量能力（P2-023 只读分页 / P2-024 映射试跑 / P2-025 能力探测）
    // 默认实现抛 E-5001（能力未开通）：P2 W5/W6 的测试桩无需实现全部能力。
    // ------------------------------------------------------------------

    /** CAP-01 room.query 列表形态：房档分页（building/bindState 下推 ERP，sort 决定稳定分页） */
    default RoomListResult listRooms(String building, BindState bindState, int page, int pageSize, RoomListSort sort,
            String traceId) {
        throw ErpErrorMapper.unsupported(Capability.ROOM_QUERY);
    }

    /** CAP-03 bill.query 列表形态：账期账单分页（status/building/sort 为平台侧过滤，见连接器实现说明） */
    default BillListResult listBills(String period, BillStatus status, String building, int page, int pageSize,
            BillListSort sort, String traceId) {
        throw ErpErrorMapper.unsupported(Capability.BILL_QUERY);
    }

    /**
     * ERP 字段字典（P2-024 映射试跑数据源）：{@code category} = room|bill|ticket|owner|all。
     *
     * <p>返回契约 {@link MappingField}（源字段/标准字段/类型/必填/转换说明），不暴露厂商表结构细节。
     */
    default List<MappingField> fieldDictionary(String category, String traceId) {
        throw ErpErrorMapper.unsupported(Capability.ROOM_QUERY);
    }

    /** 房档映射试跑样本（映射后 RoomInfo 列表，最多 {@code limit} 条） */
    default List<RoomInfo> sampleRooms(int limit, String traceId) {
        throw ErpErrorMapper.unsupported(Capability.ROOM_QUERY);
    }

    /** 账单映射试跑样本（映射后 BillInfo 列表，最多 {@code limit} 条） */
    default List<BillInfo> sampleBills(String period, int limit, String traceId) {
        throw ErpErrorMapper.unsupported(Capability.BILL_QUERY);
    }

    /** 工单映射试跑样本（映射后 TicketListItem 列表，最多 {@code limit} 条） */
    default List<TicketListItem> sampleTickets(int limit, String traceId) {
        throw ErpErrorMapper.unsupported(Capability.TICKET_LIST);
    }

    /**
     * 能力探测（P2-025）：读 ERP 能力元数据并映射为契约 {@link CapabilityState} 18 项。
     *
     * <p>{@code scenario} 是 mock-erp 测试替身的探测场景（all_supported|degraded），生产连接器应忽略它。
     */
    default List<CapabilityState> probeCapabilities(String scenario, String traceId) {
        throw ErpErrorMapper.unsupported(Capability.ROOM_QUERY);
    }
}
