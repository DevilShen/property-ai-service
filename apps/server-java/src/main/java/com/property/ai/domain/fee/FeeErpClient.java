package com.property.ai.domain.fee;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.property.ai.bff.dto.ErrorCode;
import com.property.ai.connector.Capability;
import com.property.ai.connector.ConnectorException;
import com.property.ai.connector.ConnectorProperties;
import com.property.ai.connector.ConnectorTimes;
import com.property.ai.connector.ErpErrorMapper;
import com.property.ai.connector.HttpErpClient;

/**
 * 费用域 ERP 只读门面（P3 W10，{@code /fees/*} 的 ERP 侧取数）。
 *
 * <p><b>为什么不用 {@code ErpConnector} 能力接口</b>：欠费分层（{@code /api/v1/bills?arrearsBucket=}）、
 * 费项字典（{@code /api/v1/meta/fee-items}）、账期清单（{@code /api/v1/meta}）是 mock-erp 的
 * <b>费用口径私有协议</b>，尚未进对外 {@code ErpConnector} 契约（docs/openapi/README.md §6.4 遗留 8：
 * 「ERP 侧列表/分页能力契约化」属 P5）。本类只做「厂商私有 JSON → 费用域只读模型」单向映射，
 * 出站仍走连接器唯一通道 {@link HttpErpClient}（超时/重试/熔断/错误码翻译/traceId 全复用 P2 口径），
 * 待 P5 契约化后可直接替换为 {@code ErpConnector} 方法。
 *
 * <p><b>金额纪律</b>：{@code total/outstanding/lateFee/items[].amount} 原样读取 ERP，不重算、不落库。
 * <p><b>隐私</b>：业主姓名只取 ERP 掩码字段 {@code ownerNameMasked}，不取手机号/身份证。
 */
@Component
public class FeeErpClient {

    /** ERP 列表单页上限（mock-erp 支持 500）；费用台账样本量 200 户/账期足够 */
    private static final int ERP_PAGE_SIZE = 500;
    /** 内部翻页保护 */
    private static final int MAX_PAGES = 8;

    private final HttpErpClient httpErpClient;
    private final ConnectorProperties properties;

    public FeeErpClient(HttpErpClient httpErpClient, ConnectorProperties properties) {
        this.httpErpClient = httpErpClient;
        this.properties = properties;
    }

    // ------------------------------------------------------------------
    // 只读模型（费用域内部形状；不进对外契约）
    // ------------------------------------------------------------------

    /** 账单费项行（ERP 口径金额 + 知识条目引用） */
    public record ErpBillLine(String itemCode, String name, BigDecimal amount, String knowledgeId, String note) {
    }

    /** 账单知识引用（ERP cite） */
    public record ErpCite(String kbId, String version, String title) {
    }

    /** 账单（ERP 原始口径；含 P3 透传的逾期天数/欠费账龄/滞纳金/分层） */
    public record ErpBill(
            String roomCode,
            String period,
            BigDecimal total,
            BigDecimal prevTotal,
            BigDecimal diff,
            String diffReason,
            List<ErpBillLine> items,
            BigDecimal paid,
            BigDecimal outstanding,
            BigDecimal lateFee,
            String status,
            String dueDate,
            String dataAsOf,
            boolean mock,
            Integer overdueDays,
            Integer arrearsDays,
            String arrearsBucket,
            ErpCite cite) {
    }

    /** 房档只读（费用解释需要的分摊基数 + 业主掩码 + 楼栋） */
    public record ErpRoom(
            String roomCode,
            String building,
            String ownerNameMasked,
            BigDecimal areaM2,
            String parkingNo,
            boolean mock) {
    }

    /** 费项字典条目（ERP {@code /meta/fee-items}：编码/名称/单位/公式/周期/可欠费/知识出处） */
    public record FeeItemDef(
            String itemCode,
            String name,
            String unit,
            String formula,
            String periodText,
            boolean arrearsAllowed,
            String knowledgeId,
            String note) {
    }

    // ------------------------------------------------------------------
    // 读接口
    // ------------------------------------------------------------------

    /** 账期清单（{@code /meta.dataset.periods}；缺失回退当前账期，不阻断台账） */
    public List<String> periods(String traceId) {
        JsonNode envelope = httpErpClient.call(Capability.BILL_QUERY, "GET", "/api/v1/meta",
                Map.of(), null, Map.of(), traceId);
        JsonNode periods = data(Capability.BILL_QUERY, envelope).path("dataset").path("periods");
        List<String> rows = new ArrayList<>();
        if (periods.isArray()) {
            for (JsonNode node : periods) {
                String period = node.asText("");
                if (!period.isBlank()) {
                    rows.add(period);
                }
            }
        }
        return rows.isEmpty() ? List.of(ConnectorTimes.currentPeriod()) : List.copyOf(rows);
    }

    /** 某账期全部账单（分页拉全；含未逾期与已缴，供本地分层聚合） */
    public List<ErpBill> billsOfPeriod(String period, String traceId) {
        List<ErpBill> rows = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("period", period);
            query.put("page", String.valueOf(page));
            query.put("pageSize", String.valueOf(ERP_PAGE_SIZE));
            JsonNode envelope;
            try {
                envelope = httpErpClient.call(Capability.BILL_QUERY, "GET", "/api/v1/bills", query, null,
                        Map.of(), traceId);
            } catch (ConnectorException ex) {
                // 账期无账单：ERP 404 E-3001 → 台账视为空样本（不是错误）
                if (ex.code() == ErrorCode.E_3001) {
                    return List.copyOf(rows);
                }
                throw ex;
            }
            JsonNode data = data(Capability.BILL_QUERY, envelope);
            JsonNode items = data.path("items");
            if (!items.isArray() || items.isEmpty()) {
                break;
            }
            boolean mock = envelope.path("mock").asBoolean(true);
            for (JsonNode node : items) {
                rows.add(mapBill(node, mock));
            }
            long total = data.path("total").asLong(rows.size());
            if (rows.size() >= total || items.size() < ERP_PAGE_SIZE) {
                break;
            }
        }
        return List.copyOf(rows);
    }

    /** 单账单（房号 + 账期）；无账单 → {@link ConnectorException} E-3001（费用域转 404 语义） */
    public ErpBill bill(String roomCode, String period, String traceId) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("roomCode", roomCode);
        query.put("period", period);
        JsonNode envelope = httpErpClient.call(Capability.BILL_QUERY, "GET", "/api/v1/bills", query, null,
                Map.of(), traceId);
        return mapBill(data(Capability.BILL_QUERY, envelope), envelope.path("mock").asBoolean(true));
    }

    /** 房档全量（费用台账取业主掩码/楼栋；解释取面积/车位分摊基数） */
    public List<ErpRoom> rooms(String traceId) {
        List<ErpRoom> rows = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("page", String.valueOf(page));
            query.put("pageSize", String.valueOf(ERP_PAGE_SIZE));
            query.put("sort", "roomCode_asc");
            JsonNode envelope = httpErpClient.call(Capability.ROOM_QUERY, "GET", "/api/v1/rooms", query, null,
                    Map.of(), traceId);
            JsonNode data = data(Capability.ROOM_QUERY, envelope);
            JsonNode items = data.path("items");
            if (!items.isArray() || items.isEmpty()) {
                break;
            }
            boolean mock = envelope.path("mock").asBoolean(true);
            for (JsonNode node : items) {
                rows.add(new ErpRoom(
                        text(node, "roomCode", text(node, "roomNo", null)),
                        text(node, "building", null),
                        text(node, "ownerNameMasked", null),
                        decimal(node, "areaM2"),
                        text(node, "parkingNo", null),
                        mock));
            }
            long total = data.path("total").asLong(rows.size());
            if (rows.size() >= total || items.size() < ERP_PAGE_SIZE) {
                break;
            }
        }
        return List.copyOf(rows);
    }

    /** 单房档（解释分摊基数）；房号不存在 → ConnectorException E-3001 */
    public ErpRoom room(String roomCode, String traceId) {
        JsonNode envelope = httpErpClient.call(Capability.ROOM_QUERY, "GET",
                "/api/v1/rooms/" + encodePath(roomCode), Map.of(), null, Map.of(), traceId);
        JsonNode data = data(Capability.ROOM_QUERY, envelope);
        return new ErpRoom(
                text(data, "roomCode", text(data, "roomNo", roomCode)),
                text(data, "building", null),
                text(data, "ownerNameMasked", null),
                decimal(data, "areaM2"),
                text(data, "parkingNo", null),
                envelope.path("mock").asBoolean(true));
    }

    /** 费项字典（≥8 费项；BFF 公式/分摊口径的事实源） */
    public List<FeeItemDef> feeItems(String traceId) {
        JsonNode envelope = httpErpClient.call(Capability.BILL_QUERY, "GET", "/api/v1/meta/fee-items",
                Map.of(), null, Map.of(), traceId);
        JsonNode items = data(Capability.BILL_QUERY, envelope).path("items");
        if (!items.isArray() || items.isEmpty()) {
            throw ErpErrorMapper.mapping(Capability.BILL_QUERY, "ERP 费项字典缺失（fee-items.items）");
        }
        List<FeeItemDef> rows = new ArrayList<>();
        for (JsonNode node : items) {
            String itemCode = text(node, "itemCode", null);
            if (itemCode == null) {
                throw ErpErrorMapper.mapping(Capability.BILL_QUERY, "ERP 费项字典缺 itemCode");
            }
            rows.add(new FeeItemDef(
                    itemCode,
                    text(node, "name", itemCode),
                    text(node, "unit", null),
                    text(node, "formula", null),
                    text(node, "periodText", null),
                    node.path("arrearsAllowed").asBoolean(false),
                    text(node, "knowledgeId", null),
                    text(node, "note", null)));
        }
        return List.copyOf(rows);
    }

    /** 连接器厂商标识（写进 degraded 口径，不参与金额） */
    public String vendor() {
        return properties.getVendorLabel();
    }

    // ------------------------------------------------------------------
    // 映射（厂商私有 JSON → 费用域只读模型；金额原样透传）
    // ------------------------------------------------------------------

    private static ErpBill mapBill(JsonNode node, boolean mock) {
        List<ErpBillLine> items = new ArrayList<>();
        JsonNode lines = node.path("items");
        if (lines.isArray()) {
            for (JsonNode line : lines) {
                items.add(new ErpBillLine(
                        text(line, "itemCode", null),
                        text(line, "name", text(line, "label", null)),
                        decimal(line, "amount"),
                        text(line, "knowledgeId", null),
                        text(line, "note", null)));
            }
        }
        JsonNode citeNode = node.path("cite");
        ErpCite cite = citeNode.isObject()
                ? new ErpCite(text(citeNode, "kbId", null), text(citeNode, "version", null),
                        text(citeNode, "title", null))
                : null;
        return new ErpBill(
                text(node, "roomCode", text(node, "roomNo", null)),
                text(node, "period", null),
                decimal(node, "total"),
                decimal(node, "prevTotal"),
                decimal(node, "diff"),
                text(node, "diffReason", null),
                List.copyOf(items),
                decimal(node, "paid"),
                decimal(node, "outstanding"),
                decimal(node, "lateFee"),
                text(node, "status", null),
                text(node, "dueDate", null),
                text(node, "dataAsOf", null),
                mock,
                integer(node, "overdueDays"),
                integer(node, "arrearsDays"),
                text(node, "arrearsBucket", null),
                cite);
    }

    // ------------------------------------------------------------------
    // JSON 工具
    // ------------------------------------------------------------------

    private static JsonNode data(Capability capability, JsonNode envelope) {
        JsonNode data = envelope == null ? null : envelope.get("data");
        if (data == null || data.isNull() || !data.isObject()) {
            throw ErpErrorMapper.mapping(capability, "ERP 响应缺 data 对象（费用取数）");
        }
        return data;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? fallback : text;
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.decimalValue();
        }
        String text = value.asText();
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.intValue();
        }
        String text = value.asText();
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(text.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String encodePath(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
