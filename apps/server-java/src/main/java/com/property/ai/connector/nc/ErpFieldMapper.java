package com.property.ai.connector.nc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.property.ai.bff.dto.AccountStatus;
import com.property.ai.bff.dto.BillInfo;
import com.property.ai.bff.dto.BillItem;
import com.property.ai.bff.dto.BillSource;
import com.property.ai.bff.dto.BillStatus;
import com.property.ai.bff.dto.BindState;
import com.property.ai.bff.dto.Citation;
import com.property.ai.bff.dto.ErpRef;
import com.property.ai.bff.dto.HouseholdRelation;
import com.property.ai.bff.dto.IssueScope;
import com.property.ai.bff.dto.OwnerVerifyResult;
import com.property.ai.bff.dto.RelationType;
import com.property.ai.bff.dto.ReporterType;
import com.property.ai.bff.dto.RoomInfo;
import com.property.ai.bff.dto.RoomSource;
import com.property.ai.bff.dto.Ticket;
import com.property.ai.bff.dto.TicketAuditNode;
import com.property.ai.bff.dto.TicketCategory;
import com.property.ai.bff.dto.TicketCreateRequest;
import com.property.ai.bff.dto.TicketFeedbackRequest;
import com.property.ai.bff.dto.TicketListItem;
import com.property.ai.bff.dto.TicketStatus;
import com.property.ai.bff.dto.TicketTransitionAction;
import com.property.ai.bff.dto.TicketUrgency;
import com.property.ai.connector.Capability;
import com.property.ai.connector.ConnectorException;
import com.property.ai.connector.ErpErrorMapper;

/**
 * 用友 NC（mock-erp 测试替身）↔ 契约 DTO 的字段/字典/状态映射（纯函数，无 IO，可直接单测）。
 *
 * <p>映射边界（契约 §2 / README §3）：
 * <ul>
 *   <li>ERP 侧路径与字段是**厂商私有**，不进对外契约；本类只做「厂商结构 → 契约 DTO」单向映射；</li>
 *   <li>金额不做任何重算：{@code total/prevTotal/diff/items[].amount/paid/outstanding/lateFee}
 *       全部来自 ERP；{@code due} 在 mock-erp 未单列时映射 ERP 的 {@code outstanding}（同口径：本期未缴），
 *       注释已标明真实 NC 字段（{@code dqyj}）；</li>
 *   <li>字典未匹配/状态未覆盖 → 抛 {@code E-5002}（映射失败，转人工，不猜测，设计 §26.2-3）；</li>
 *   <li>{@code cite} 缺失（差额为 0 时 ERP 不回）按 K-002 V1.1 口径补齐，避免契约必填字段缺失。</li>
 * </ul>
 */
public final class ErpFieldMapper {

    /** mock-erp 未回项目名/引用时的兜底（契约必填字段不能缺） */
    private static final String FALLBACK_KB_ID = "K-002";
    private static final String FALLBACK_KB_VERSION = "V1.1";
    private static final String FALLBACK_KB_TITLE = "公摊电费分摊口径";

    private ErpFieldMapper() {
    }

    // ------------------------------------------------------------------
    // owner.verify
    // ------------------------------------------------------------------

    public static OwnerVerifyResult mapOwnerVerify(JsonNode data, String projectName, boolean mock) {
        boolean verified = data.path("verified").asBoolean(false);
        boolean locked = data.path("locked").asBoolean(false);
        Integer attemptsLeft = data.hasNonNull("remainingAttempts") ? data.path("remainingAttempts").asInt() : null;
        String roomCode = textOr(data, "roomCode", "");
        String ownerNameMasked = verified ? textOr(data, "ownerNameMasked", "") : "";
        return new OwnerVerifyResult(verified, roomCode, ownerNameMasked, projectName, attemptsLeft, locked, mock);
    }

    // ------------------------------------------------------------------
    // room.query
    // ------------------------------------------------------------------

    public static RoomInfo mapRoom(JsonNode data, boolean mock) {
        String dataAsOf = textOr(data, "dataAsOf", null);
        return new RoomInfo(
                textOr(data, "householdId", ""),
                textOr(data, "projectId", ""),
                textOr(data, "roomCode", textOr(data, "roomNo", "")),
                textOr(data, "building", ""),
                textOr(data, "unit", ""),
                textOr(data, "room", ""),
                decimalOrNull(data, "areaM2"),
                textOr(data, "layout", null),
                textOr(data, "ownerNameMasked", ""),
                enumValue(data, "bindState", BindState::fromValue, Capability.ROOM_QUERY),
                textOr(data, "parkingNo", null),
                enumValue(data, "accountStatus", AccountStatus::fromValue, Capability.ROOM_QUERY),
                data.path("overdueDays").asInt(0),
                decimalOrNull(data, "arrearsAmount"),
                mapRelations(data.path("relations")),
                textList(data.path("tags")),
                enumValue(data, "source", RoomSource::fromValue, Capability.ROOM_QUERY),
                mock,
                dataAsOf,
                textOr(data, "updatedAt", dataAsOf));
    }

    private static List<HouseholdRelation> mapRelations(JsonNode relations) {
        if (relations == null || !relations.isArray()) {
            return List.of();
        }
        List<HouseholdRelation> rows = new ArrayList<>();
        for (JsonNode node : relations) {
            RelationType type = enumValue(node, "relationType", RelationType::fromValue, Capability.ROOM_QUERY);
            rows.add(new HouseholdRelation(
                    type,
                    textOr(node, "nameMasked", ""),
                    textOr(node, "phoneMasked", null),
                    node.hasNonNull("isPrimary") ? node.path("isPrimary").asBoolean() : null));
        }
        return rows;
    }

    // ------------------------------------------------------------------
    // bill.query
    // ------------------------------------------------------------------

    public static BillInfo mapBill(JsonNode data, boolean mock) {
        BigDecimal total = decimalOrNull(data, "total");
        BigDecimal paid = decimalOrNull(data, "paid");
        BigDecimal outstanding = decimalOrNull(data, "outstanding");
        // due（本期待缴）：mock-erp 未单列 → 映射 ERP outstanding（同口径「本期未缴」）；
        // 真实 NC 用应收余额字段 dqyj/ysje，切真实环境只改这里，不改契约。
        BigDecimal due = decimalOrNull(data, "due");
        if (due == null) {
            due = outstanding != null ? outstanding : paid != null && total != null ? total.subtract(paid) : total;
        }
        // P3 W10（契约 v1.1.0）：BillInfo 末尾追加的可选字段透传（ERP 有则回填，无则 null 不序列化，
        // P2 /bills 响应形状不变；金额/天数均原样取 ERP，不重算）。
        Integer overdueDays = intOrNull(data, "overdueDays");
        Integer arrearsDays = intOrNull(data, "arrearsDays");
        String explainRef = textOr(data, "explainRef", null);
        return new BillInfo(
                textOr(data, "period", ""),
                total,
                due,
                mapBillItems(data.path("items")),
                decimalOrNull(data, "prevTotal"),
                decimalOrNull(data, "diff"),
                mapCitation(data.path("cite")),
                textOr(data, "diffReason", null),
                textOr(data, "billNo", null),
                textOr(data, "householdId", null),
                textOr(data, "projectId", null),
                textOr(data, "roomCode", textOr(data, "roomNo", null)),
                paid,
                outstanding,
                decimalOrNull(data, "lateFee"),
                enumValue(data, "status", BillStatus::fromValue, Capability.BILL_QUERY),
                textOr(data, "dueDate", null),
                textOr(data, "currency", "CNY"),
                enumValue(data, "source", BillSource::fromValue, Capability.BILL_QUERY),
                textOr(data, "dataAsOf", null),
                mock,
                null,
                overdueDays,
                arrearsDays,
                explainRef);
    }

    /** 可选整数字段（ERP 未回传/非数字 → null，不猜测为 0；P3 W10 逾期天数/账龄用）。 */
    private static Integer intOrNull(JsonNode data, String field) {
        JsonNode node = data == null ? null : data.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.intValue();
        }
        String text = node.asText();
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(text.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static List<BillItem> mapBillItems(JsonNode items) {
        if (items == null || !items.isArray()) {
            return List.of();
        }
        List<BillItem> rows = new ArrayList<>();
        for (JsonNode node : items) {
            rows.add(new BillItem(
                    textOr(node, "label", textOr(node, "name", "")),
                    decimalOrNull(node, "amount"),
                    textOr(node, "note", null),
                    textOr(node, "itemCode", null),
                    textOr(node, "knowledgeId", null)));
        }
        return rows;
    }

    private static Citation mapCitation(JsonNode cite) {
        if (cite == null || cite.isNull() || cite.isMissingNode()) {
            // 差额为 0 时 mock-erp 不回 cite；契约 BillInfo.cite 必填 → 按 K-002 V1.1 口径补齐（只做引用，不参与金额计算）
            return new Citation(FALLBACK_KB_ID, FALLBACK_KB_VERSION, FALLBACK_KB_TITLE, null, null);
        }
        return new Citation(
                textOr(cite, "kbId", FALLBACK_KB_ID),
                textOr(cite, "version", FALLBACK_KB_VERSION),
                textOr(cite, "title", FALLBACK_KB_TITLE),
                textOr(cite, "effectiveDate", null),
                textOr(cite, "owner", null));
    }

    // ------------------------------------------------------------------
    // ticket.*
    // ------------------------------------------------------------------

    public static Ticket mapTicket(JsonNode data, ErpRef erp, boolean mock) {
        String ticketNo = textOr(data, "ticketNo", "");
        TicketStatus status = mapStatus(textOr(data, "status", null));
        return new Ticket(
                ticketNo,
                textOr(data, "projectId", ""),
                textOr(data, "householdId", ""),
                textOr(data, "roomCode", textOr(data, "roomNo", "")),
                enumValue(data, "category", TicketCategory::fromValue, Capability.TICKET_STATUS),
                enumValue(data, "urgency", TicketUrgency::fromValue, Capability.TICKET_STATUS),
                // ERP 无独立 location 字段：只用 data.location（真实 NC 映射），缺失时留空由领域层用
                // 平台侧报修要素补齐（不得把 symptom 当 location 覆盖业主填报的位置）
                textOr(data, "location", ""),
                enumValue(data, "issueScope", IssueScope::fromValue, Capability.TICKET_STATUS),
                textOr(data, "duration", ""),
                textOr(data, "expectTime", textOr(data, "slot", "")),
                textOr(data, "note", null),
                textOr(data, "symptom", null),
                status,
                textOr(data, "statusText", ""),
                textOr(data, "team", ""),
                textOr(data, "etaText", textOr(data, "eta", "")),
                mapNodes(data.path("nodes")),
                data.path("version").asInt(1),
                data.path("aiCreated").asBoolean(true),
                mock,
                erp,
                textOr(data, "traceId", null),
                textOr(data, "createdAt", ""),
                textOr(data, "updatedAt", ""));
    }

    public static TicketListItem mapTicketListItem(JsonNode data, boolean mock) {
        TicketStatus status = mapStatus(textOr(data, "status", null));
        return new TicketListItem(
                textOr(data, "ticketNo", ""),
                textOr(data, "roomCode", textOr(data, "roomNo", "")),
                enumValue(data, "category", TicketCategory::fromValue, Capability.TICKET_LIST),
                enumValue(data, "urgency", TicketUrgency::fromValue, Capability.TICKET_LIST),
                status,
                textOr(data, "statusText", ""),
                textOr(data, "team", ""),
                textOr(data, "etaText", textOr(data, "eta", "")),
                data.path("version").asInt(1),
                data.path("aiCreated").asBoolean(true),
                mock,
                textOr(data, "createdAt", ""),
                textOr(data, "updatedAt", ""));
    }

    public static List<TicketAuditNode> mapNodes(JsonNode nodes) {
        if (nodes == null || !nodes.isArray()) {
            return List.of();
        }
        List<TicketAuditNode> rows = new ArrayList<>();
        for (JsonNode node : nodes) {
            TicketStatus status = mapStatus(textOr(node, "code", textOr(node, "status", null)));
            rows.add(new TicketAuditNode(
                    status,
                    textOr(node, "name", textOr(node, "statusText", "")),
                    textOr(node, "at", ""),
                    textOr(node, "who", null),
                    textOr(node, "ownerText", null)));
        }
        return rows;
    }

    /**
     * ERP 状态字典 → 契约 7 节点。
     *
     * <p>mock-erp 使用 4 节点子集（accepted/assigned/onsite/completed）。
     * 未覆盖状态（如 cancelled）→ 抛 {@code E-5002}（“状态未知并转人工”，不猜测，设计 §26.2-3）。
     */
    public static TicketStatus mapStatus(String erpStatus) {
        if (erpStatus == null || erpStatus.isBlank()) {
            throw ErpErrorMapper.mapping(Capability.TICKET_STATUS, "ERP 未返回工单状态");
        }
        return switch (erpStatus.trim().toLowerCase()) {
            case "created" -> TicketStatus.CREATED;
            case "accepted" -> TicketStatus.ACCEPTED;
            case "assigned", "dispatched" -> TicketStatus.DISPATCHED;
            case "onsite", "on_site" -> TicketStatus.ON_SITE;
            case "completed" -> TicketStatus.COMPLETED;
            case "reviewed" -> TicketStatus.REVIEWED;
            case "archived" -> TicketStatus.ARCHIVED;
            default -> throw ErpErrorMapper.mapping(Capability.TICKET_STATUS,
                    "ERP 未覆盖的工单状态：" + erpStatus + "（已转人工，不猜测）");
        };
    }

    /**
     * 契约状态 → ERP 状态（用于列表筛选下推）；null 表示 ERP 侧无法过滤（CREATED/REVIEWED/ARCHIVED 为平台侧状态），
     * 由平台侧本地过滤。
     */
    public static String erpStatus(TicketStatus status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case ACCEPTED -> "accepted";
            case DISPATCHED -> "assigned";
            case ON_SITE -> "onsite";
            case COMPLETED -> "completed";
            case CREATED, REVIEWED, ARCHIVED -> null;
        };
    }

    // ------------------------------------------------------------------
    // 请求映射：契约 → ERP 私有载荷
    // ------------------------------------------------------------------

    public static Map<String, Object> toErpCreatePayload(TicketCreateRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("roomCode", request.roomCode());
        payload.put("category", request.category() == null ? null : request.category().value());
        payload.put("urgency", request.urgency() == null ? null : request.urgency().value());
        payload.put("issueScope", request.downstairs() == null ? "none" : request.downstairs().value());
        payload.put("symptom", buildSymptom(request));
        if (request.note() != null && !request.note().isBlank()) {
            payload.put("description", request.note());
        }
        payload.put("expectTime", request.slot());
        payload.put("duration", request.duration());
        payload.put("reporterType", request.reporterType() == null
                ? ReporterType.OWNER.value()
                : request.reporterType().value());
        if (request.contactTail4() != null && !request.contactTail4().isBlank()) {
            payload.put("contactTail4", request.contactTail4());
        }
        payload.put("aiCreated", true);
        return payload;
    }

    private static String buildSymptom(TicketCreateRequest request) {
        String location = request.location() == null ? "" : request.location().trim();
        StringBuilder symptom = new StringBuilder(location);
        if (request.downstairs() == IssueScope.DOWNSTAIRS) {
            symptom.append("，已漏到楼下");
        }
        return symptom.toString();
    }

    public static Map<String, Object> toErpTransitionPayload(TicketTransitionAction action,
            Map<String, Object> payload) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", action.value());
        Map<String, Object> source = payload == null ? Map.of() : payload;
        switch (action) {
            case DISPATCH -> {
                putIfPresent(result, "team", source.get("team"));
                putIfPresent(result, "etaText", source.get("eta"));
                // v0.3.1 派单有名字段：接单人/技能标签回写 ERP（ERP 侧 who 优先取 assignee）
                putIfPresent(result, "assignee", source.get("assignee"));
                putIfPresent(result, "skillTag", source.get("skillTag"));
            }
            case ON_SITE -> putIfPresent(result, "who", source.get("who"));
            case COMPLETE -> {
                // CAP-09 ticket.close 完工回写：resolution 为结案说明（业主可见，ERP 侧落 resolution 字段）
                putIfPresent(result, "resolution", source.get("resolution"));
                putIfPresent(result, "erpCloseRef", source.get("erpCloseRef"));
                putIfPresent(result, "note", source.get("note"));
                putIfPresent(result, "who", source.get("who"));
                putIfPresent(result, "ownerText", source.get("ownerText"));
                putIfPresent(result, "finishedAt", source.get("finishedAt"));
            }
            case RESCHEDULE -> {
                putIfPresent(result, "expectTime", source.get("slot"));
                putIfPresent(result, "etaText", source.get("eta"));
                putIfPresent(result, "reason", source.get("reason"));
            }
            case URGE -> {
                putIfPresent(result, "reason", source.get("reason"));
                putIfPresent(result, "channel", source.get("channel"));
            }
        }
        return result;
    }

    public static Map<String, Object> toErpFeedbackPayload(TicketFeedbackRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rating", request.score());
        if (request.tags() != null && !request.tags().isEmpty()) {
            payload.put("tags", request.tags());
        }
        if (request.note() != null && !request.note().isBlank()) {
            payload.put("comment", request.note());
        }
        payload.put("channel", "platform");
        return payload;
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && !(value instanceof String text && text.isBlank())) {
            target.put(key, value);
        }
    }

    // ------------------------------------------------------------------
    // JSON 工具
    // ------------------------------------------------------------------

    public static String textOr(JsonNode node, String field, String fallback) {
        if (node == null) {
            return fallback;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        String text = value.asText();
        return text == null || text.isEmpty() ? fallback : text;
    }

    public static BigDecimal decimalOrNull(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
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
            throw ErpErrorMapper.mapping(Capability.BILL_QUERY, "金额字段不是定点数：" + field + "=" + text);
        }
    }

    public static List<String> textList(JsonNode array) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<String> rows = new ArrayList<>();
        for (JsonNode node : array) {
            if (node != null && !node.isNull()) {
                rows.add(node.asText());
            }
        }
        return rows;
    }

    /**
     * 枚举映射：ERP 小写 code → 契约枚举（枚举自带 {@code JsonCreator fromValue}）。
     * 未匹配 → {@code E-5002}（映射失败，转人工）。
     */
    public static <E> E enumValue(JsonNode node, String field, java.util.function.Function<String, E> parser,
            Capability capability) {
        String text = textOr(node, field, null);
        if (text == null) {
            return null;
        }
        try {
            return parser.apply(text);
        } catch (IllegalArgumentException ex) {
            throw ErpErrorMapper.mapping(capability, "ERP 字典未匹配（" + field + "=" + text + "），已转人工");
        }
    }

    /** 便捷：构造 ERP 引用（真实 NC 用 ERP 单号；P2 mock 用 NC- 前缀映射口径） */
    public static ErpRef erpRef(String vendor, String ticketNo) {
        return new ErpRef(vendor, ticketNo == null || ticketNo.isBlank() ? "" : "NC-" + ticketNo);
    }

    /** 便捷：ERP 解析失败/缺字段统一抛映射异常/缺字段场景统一抛映射异常 */
    public static ConnectorException missingData(Capability capability, String field) {
        return ErpErrorMapper.mapping(capability, "ERP 响应缺少必需字段：" + field);
    }
}
