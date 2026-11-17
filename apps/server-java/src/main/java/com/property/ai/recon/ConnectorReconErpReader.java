package com.property.ai.recon;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.property.ai.bff.dto.BillInfo;
import com.property.ai.bff.dto.RoomInfo;
import com.property.ai.bff.dto.TicketListItem;
import com.property.ai.connector.Capability;
import com.property.ai.connector.ConnectorException;
import com.property.ai.connector.ErpConnector;
import com.property.ai.connector.ErpErrorMapper;
import com.property.ai.connector.HttpErpClient;

/**
 * {@link ReconErpReader} 的生产实现：ERP 原值走连接器出站客户端（{@link HttpErpClient}，带
 * 超时/重试/熔断/错误码翻译/故障注入），平台展示快照走 {@link ErpConnector} 的 BFF 读路径。
 *
 * <p>为什么原值不新增 {@code ErpConnector} 能力方法：P2 第 1 周的 {@code ErpConnector} 只声明了契约需要的
 * 单对象能力（{@code bill.query} 按房号+账期）；对账需要的是 ERP 侧**分页列表**（ERP 私有端点，
 * 不进对外契约）。因此对账复用连接器出站点对点读取列表，但仍受同一套超时/重试/熔断/降级约束，
 * 且**不绕过连接器直连 ERP、不改连接器契约**（P2-014：连接器只读引用）。
 */
@Component
public class ConnectorReconErpReader implements ReconErpReader {

    /** ERP 侧单页上限（mock-erp：pageSize ≤ 500；真实 NC 按厂商分页上限，切真实环境只改这里） */
    private static final int ERP_MAX_PAGE_SIZE = 500;

    private final ErpConnector connector;
    private final HttpErpClient httpErpClient;

    public ConnectorReconErpReader(ErpConnector connector, HttpErpClient httpErpClient) {
        this.connector = connector;
        this.httpErpClient = httpErpClient;
    }

    @Override
    public List<JsonNode> readBillRows(String period, int limit, String traceId) {
        List<JsonNode> rows = new ArrayList<>();
        int page = 1;
        while (rows.size() < limit) {
            int pageSize = Math.min(ERP_MAX_PAGE_SIZE, Math.max(1, limit - rows.size()));
            Map<String, String> query = new LinkedHashMap<>();
            if (period != null && !period.isBlank()) {
                query.put("period", period);
            }
            query.put("page", String.valueOf(page));
            query.put("pageSize", String.valueOf(pageSize));
            JsonNode envelope = httpErpClient.call(Capability.BILL_QUERY, "GET", "/api/v1/bills", query, null,
                    Map.of(), traceId);
            List<JsonNode> items = dataArray(Capability.BILL_QUERY, envelope, "items");
            if (items.isEmpty()) {
                break;
            }
            for (JsonNode item : items) {
                if (rows.size() >= limit) {
                    break;
                }
                rows.add(item);
            }
            if (items.size() < pageSize) {
                break;
            }
            page++;
        }
        return rows;
    }

    @Override
    public BillInfo readBillDisplay(String roomCode, String period, String traceId) {
        return connector.queryBill(roomCode, period, traceId);
    }

    @Override
    public JsonNode readRoomRow(String roomCode, String traceId) {
        JsonNode envelope = httpErpClient.call(Capability.ROOM_QUERY, "GET",
                "/api/v1/rooms/" + encodePath(roomCode), Map.of(), null, Map.of(), traceId);
        return dataObject(Capability.ROOM_QUERY, envelope);
    }

    @Override
    public RoomInfo readRoomDisplay(String roomCode, String traceId) {
        return connector.queryRoom(roomCode, traceId);
    }

    @Override
    public List<JsonNode> readTicketRows(int limit, String traceId) {
        List<JsonNode> rows = new ArrayList<>();
        int page = 1;
        while (rows.size() < limit) {
            int pageSize = Math.min(ERP_MAX_PAGE_SIZE, Math.max(1, limit - rows.size()));
            Map<String, String> query = new LinkedHashMap<>();
            query.put("page", String.valueOf(page));
            query.put("pageSize", String.valueOf(pageSize));
            JsonNode envelope = httpErpClient.call(Capability.TICKET_LIST, "GET", "/api/v1/tickets", query, null,
                    Map.of(), traceId);
            List<JsonNode> items = dataArray(Capability.TICKET_LIST, envelope, "items");
            if (items.isEmpty()) {
                break;
            }
            for (JsonNode item : items) {
                if (rows.size() >= limit) {
                    break;
                }
                rows.add(item);
            }
            if (items.size() < pageSize) {
                break;
            }
            page++;
        }
        return rows;
    }

    @Override
    public List<TicketListItem> readTicketDisplay(int limit, String traceId) {
        int pageSize = Math.min(ERP_MAX_PAGE_SIZE, Math.max(1, limit));
        List<TicketListItem> items = connector.listTickets(null, null, 1, pageSize, traceId).items();
        return items.size() <= limit ? items : List.copyOf(items.subList(0, limit));
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private JsonNode dataObject(Capability capability, JsonNode envelope) {
        JsonNode data = envelope == null ? null : envelope.get("data");
        if (data == null || data.isNull() || !data.isObject()) {
            throw ErpErrorMapper.mapping(capability, "ERP 响应缺少 data 对象（对账读源不可用）");
        }
        return data;
    }

    private List<JsonNode> dataArray(Capability capability, JsonNode envelope, String field) {
        JsonNode data = dataObject(capability, envelope);
        JsonNode array = data.get(field);
        if (array == null || !array.isArray()) {
            throw ErpErrorMapper.mapping(capability, "ERP 响应缺少分页数组：" + field);
        }
        List<JsonNode> rows = new ArrayList<>(array.size());
        array.forEach(rows::add);
        return rows;
    }

    private static String encodePath(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** 供单测/排障断言：连接器健康状态（不触发调用） */
    public boolean connectorHealthy() {
        try {
            return connector.health() != null && connector.health().available();
        } catch (ConnectorException ex) {
            return false;
        }
    }
}
