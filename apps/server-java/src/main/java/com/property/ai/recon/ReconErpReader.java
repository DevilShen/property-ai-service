package com.property.ai.recon;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.property.ai.bff.dto.BillInfo;
import com.property.ai.bff.dto.RoomInfo;
import com.property.ai.bff.dto.TicketListItem;

/**
 * 对账读源（ERP 实时只读 + 平台展示快照）。
 *
 * <p>拆成接口的目的：{@link ReconService} 的对账/统计/异步逻辑可脱离 HTTP 单测；
 * 生产实现固定为 {@link ConnectorReconErpReader}（经连接器，不直连 ERP、不落库）。
 *
 * <p>约定：
 * <ul>
 *   <li>「ERP 原值」方法返回 ERP 私有载荷（{@link JsonNode}），**不做任何平台侧补齐/重算**；</li>
 *   <li>「平台展示快照」方法走 BFF 真实读路径（{@code ErpConnector}），即前端看到的契约 DTO；</li>
 *   <li>失败一律抛 {@code ConnectorException}（带 17 类错误码），由调用方决定计入差异还是判 run 失败。</li>
 * </ul>
 */
public interface ReconErpReader {

    /** ERP 账单分页原值（{@code GET /api/v1/bills?period=&page=&pageSize=}，ERP 私有载荷数组） */
    List<JsonNode> readBillRows(String period, int limit, String traceId);

    /** 平台展示快照：BFF 账单读路径（CAP-03 bill.query，含契约必填字段补齐口径） */
    BillInfo readBillDisplay(String roomCode, String period, String traceId);

    /** ERP 房档原值（{@code GET /api/v1/rooms/{roomCode}} 的 data 节点） */
    JsonNode readRoomRow(String roomCode, String traceId);

    /** 平台展示快照：BFF 房档读路径（CAP-01 room.query） */
    RoomInfo readRoomDisplay(String roomCode, String traceId);

    /** ERP 工单分页原值（{@code GET /api/v1/tickets?page=&pageSize=}，ERP 私有载荷数组） */
    List<JsonNode> readTicketRows(int limit, String traceId);

    /** 平台展示快照：BFF 工单列表读路径（CAP-08 ticket.list） */
    List<TicketListItem> readTicketDisplay(int limit, String traceId);
}
