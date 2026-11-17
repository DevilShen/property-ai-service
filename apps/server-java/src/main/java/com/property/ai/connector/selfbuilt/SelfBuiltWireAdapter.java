package com.property.ai.connector.selfbuilt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 自研 ERP「自有形态 3.0」wire 协议适配（P5-012 / task-65）。
 *
 * <h2>为什么需要 Wire 层</h2>
 * 第 3 套连接器代表客户**自研/私有化物业 ERP**，其开放协议与用友 NC、金蝶 EAS 都不同（字段名、状态机、
 * 账期写法：{@code house_block}/{@code room_no}/{@code fee_total}/{@code workorder_no}）。
 * 本仓库内它是 **mock 测试替身**（不接真实 ERP），数据面复用已起的 mock
 * （{@code property.erp.self-built-base-url}，默认 NC mock :4200）：本类负责
 * 「**mock 数据面（NC 形态）↔ 自有形态 3.0 wire**」的双向协议模拟，这样：
 * <ul>
 *   <li>真实接入自研 ERP 时，只把本类换成真实 HTTP wire（字段名不变），
 *       {@link SelfBuiltFieldMapper} 与契约 DTO 都不动；</li>
 *   <li>自研 ERP 侧联调/单测可以直接给「自有形态」payload，本类检测到自有字段就<b>原样透传</b>
 *       （{@code ensureSelfBuilt} 的双形态识别），不会二次转换；</li>
 *   <li>出站请求参数按自有字段名声明（{@code room_no}/{@code phone_tail}/{@code bill_period}），
 *       再由本类翻译成 mock 数据面参数——请求方向也保持「自有形态」语义。</li>
 * </ul>
 *
 * <h2>边界</h2>
 * 只做键名/字典/枚举的形状转换，<b>不做任何金额重算、不伪造缺失字段</b>；未识别的键保持原样透传，
 * 让 {@link SelfBuiltFieldMapper} 决定是映射还是抛 {@code E-5002}（映射失败转人工）。
 * 所有转换都是纯函数（无 IO），可直接单测。
 */
public final class SelfBuiltWireAdapter {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /** 实体类型（决定自有字段检测与转换方向） */
    public enum Entity {
        ROOM, BILL, TICKET, OWNER_VERIFY
    }

    private SelfBuiltWireAdapter() {
    }

    // ==================================================================
    // 自有形态识别（双形态：真实自研 wire / mock 数据面）
    // ==================================================================

    /** 判断 payload 是否已经是自有形态（真实自研 ERP 或自研桩返回），是则不再转换 */
    public static boolean isSelfBuiltShape(JsonNode node, Entity entity) {
        if (node == null || !node.isObject()) {
            return false;
        }
        return switch (entity) {
            case ROOM -> node.has("house_block") || node.has("house_key") || node.has("area_sqm");
            case BILL -> node.has("fee_total") || node.has("bill_key") || node.has("bill_period");
            case TICKET -> node.has("workorder_no") || node.has("wo_state");
            case OWNER_VERIFY -> node.has("verify_result") || node.has("attempts_left");
        };
    }

    /**
     * 归一为自有形态：已是自有形态 → 原样返回；否则按 mock 数据面（NC 形态）转换。
     *
     * <p>调用方（{@link SelfBuiltErpConnector}）拿到结果后只交给 {@link SelfBuiltFieldMapper}，
     * 因此「真实自研 wire」与「mock 替身数据面」两条路径共用同一套映射逻辑。
     */
    public static JsonNode ensureSelfBuilt(JsonNode node, Entity entity) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (isSelfBuiltShape(node, entity)) {
            return node;
        }
        return switch (entity) {
            case ROOM -> fromDataPlaneRoom(node);
            case BILL -> fromDataPlaneBill(node);
            case TICKET -> fromDataPlaneTicket(node);
            case OWNER_VERIFY -> fromDataPlaneOwnerVerify(node);
        };
    }

    /** 列表归一（保持顺序） */
    public static ArrayNode ensureSelfBuiltList(JsonNode items, Entity entity) {
        ArrayNode rows = NODES.arrayNode();
        if (items == null || !items.isArray()) {
            return rows;
        }
        for (JsonNode node : items) {
            rows.add(ensureSelfBuilt(node, entity));
        }
        return rows;
    }

    // ==================================================================
    // 出站请求参数：自有形态 → mock 数据面参数
    // ==================================================================

    /** 房号参数：自有形态 {@code room_no} 语义 → 数据面 {@code roomCode}（同值，键名不同） */
    public static String roomCodeParam(String roomNo) {
        return roomNo == null ? null : roomNo.trim();
    }

    /** 账期参数：自有形态 {@code 202611} → 数据面 {@code 2026-11}（数据面只认 6 位子集，反向归一） */
    public static String periodParam(String period) {
        if (period == null) {
            return null;
        }
        String trimmed = period.trim();
        if (trimmed.matches("\\d{6}")) {
            return trimmed.substring(0, 4) + "-" + trimmed.substring(4);
        }
        return trimmed;
    }

    /** 工单状态参数：契约状态 → 自有形态 {@code wo_state}（列表筛选下推；null = 不下推） */
    public static String woStateParam(com.property.ai.bff.dto.TicketStatus status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case CREATED -> "NEW";
            case ACCEPTED -> "ACCEPTED";
            case DISPATCHED -> "DISPATCHED";
            case ON_SITE -> "ON_SITE";
            case COMPLETED -> "DONE";
            case REVIEWED -> "REVIEWED";
            case ARCHIVED -> "ARCHIVED";
        };
    }

    // ==================================================================
    // mock 数据面（NC 形态） → 自有形态
    // ==================================================================

    private static ObjectNode fromDataPlaneRoom(JsonNode source) {
        ObjectNode row = NODES.objectNode();
        String roomCode = text(source, "roomCode", text(source, "roomNo", ""));
        row.put("house_key", text(source, "householdId", roomCode));
        row.put("project_key", text(source, "projectId", ""));
        row.put("house_block", blockOf(source, roomCode));
        row.put("unit_no", numericHead(text(source, "unit", unitOf(roomCode))));
        row.put("room_no", text(source, "room", roomOf(roomCode)));
        row.put("room_code", roomCode);
        copy(source, row, "areaM2", "area_sqm");
        row.put("layout_type", text(source, "layout", ""));
        String bindState = text(source, "bindState", "");
        row.put("bind_flag", switch (bindState) {
            case "bound" -> "Y";
            case "unbound" -> "N";
            default -> "P";
        });
        row.put("park_space", text(source, "parkingNo", ""));
        row.put("acct_state", text(source, "accountStatus", "").toUpperCase());
        copy(source, row, "overdueDays", "overdue_days");
        copy(source, row, "arrearsAmount", "arrears_amount");
        row.put("data_as_of", text(source, "dataAsOf", ""));
        row.put("updated_at", text(source, "updatedAt", ""));
        row.set("tag_list", source.path("tags").isArray() ? source.path("tags") : NODES.arrayNode());
        row.set("rel_list", relationsFromDataPlane(source.path("relations")));
        return row;
    }

    private static ObjectNode fromDataPlaneBill(JsonNode source) {
        ObjectNode row = NODES.objectNode();
        row.put("bill_key", text(source, "billNo", ""));
        row.put("bill_period", text(source, "period", ""));
        copy(source, row, "total", "fee_total");
        copy(source, row, "paid", "fee_paid");
        copy(source, row, "outstanding", "fee_unpaid");
        copy(source, row, "due", "fee_due");
        copy(source, row, "prevTotal", "prev_fee_total");
        copy(source, row, "diff", "fee_diff");
        copy(source, row, "lateFee", "late_fee");
        row.put("bill_state", text(source, "status", "").toUpperCase());
        row.put("due_date", text(source, "dueDate", ""));
        row.put("currency_code", text(source, "currency", "CNY"));
        row.put("room_code", text(source, "roomCode", text(source, "roomNo", "")));
        row.put("house_key", text(source, "householdId", ""));
        row.put("project_key", text(source, "projectId", ""));
        row.put("data_as_of", text(source, "dataAsOf", ""));
        row.put("diff_reason", text(source, "diffReason", ""));
        row.set("fee_items", feeItemsFromDataPlane(source.path("items")));
        if (source.has("cite") && source.path("cite").isObject()) {
            // 知识引用原样透传（契约 BillInfo.cite 由 NC 映射器读取 kbId/version/title，字段名即契约口径）
            row.set("kb_cite", source.path("cite"));
        }
        return row;
    }

    private static ObjectNode fromDataPlaneTicket(JsonNode source) {
        ObjectNode row = NODES.objectNode();
        row.put("workorder_no", text(source, "ticketNo", ""));
        row.put("project_key", text(source, "projectId", ""));
        row.put("house_key", text(source, "householdId", ""));
        row.put("room_code", text(source, "roomCode", text(source, "roomNo", "")));
        row.put("wo_category", categoryOf(text(source, "category", "")));
        row.put("wo_urgency", text(source, "urgency", "normal").toUpperCase());
        row.put("wo_state", woStateOf(text(source, "status", "")));
        row.put("state_label", text(source, "statusText", ""));
        row.put("site", text(source, "location", ""));
        row.put("scope_flag", text(source, "issueScope", "none").toUpperCase());
        row.put("duration_text", text(source, "duration", ""));
        row.put("expect_slot", text(source, "expectTime", ""));
        row.put("note_text", text(source, "note", ""));
        row.put("symptom_text", text(source, "symptom", ""));
        row.put("team_name", text(source, "team", ""));
        row.put("eta_text", text(source, "etaText", ""));
        copy(source, row, "version", "version_no");
        copy(source, row, "aiCreated", "ai_flag");
        row.put("report_at", text(source, "createdAt", ""));
        row.put("modify_at", text(source, "updatedAt", ""));
        row.set("wo_nodes", source.path("nodes").isArray() ? source.path("nodes") : NODES.arrayNode());
        return row;
    }

    private static ObjectNode fromDataPlaneOwnerVerify(JsonNode source) {
        ObjectNode row = NODES.objectNode();
        row.put("verify_result", source.path("verified").asBoolean(false));
        row.put("room_code", text(source, "roomCode", ""));
        row.put("owner_name", text(source, "ownerNameMasked", ""));
        copy(source, row, "remainingAttempts", "attempts_left");
        row.put("lock_flag", source.path("locked").asBoolean(false));
        return row;
    }

    private static ArrayNode relationsFromDataPlane(JsonNode relations) {
        ArrayNode rows = NODES.arrayNode();
        if (relations == null || !relations.isArray()) {
            return rows;
        }
        for (JsonNode node : relations) {
            ObjectNode row = NODES.objectNode();
            row.put("rel_type", text(node, "relationType", "owner"));
            row.put("name_masked", text(node, "nameMasked", ""));
            row.put("phone_masked", text(node, "phoneMasked", ""));
            row.put("primary_flag", node.path("isPrimary").asBoolean(false));
            rows.add(row);
        }
        return rows;
    }

    private static ArrayNode feeItemsFromDataPlane(JsonNode items) {
        ArrayNode rows = NODES.arrayNode();
        if (items == null || !items.isArray()) {
            return rows;
        }
        for (JsonNode node : items) {
            ObjectNode row = NODES.objectNode();
            row.put("fee_code", text(node, "itemCode", ""));
            row.put("fee_name", text(node, "name", ""));
            row.set("fee_amount", node.path("amount").isMissingNode() ? NODES.nullNode() : node.path("amount"));
            row.put("kb_id", text(node, "knowledgeId", ""));
            row.put("fee_note", text(node, "note", ""));
            rows.add(row);
        }
        return rows;
    }

    // ==================================================================
    // 字典反向映射（数据面 → 自有形态；未知值原样透传，由 mapper 抛 E-5002）
    // ==================================================================

    /** 契约工单分类 → 自有分类码（PLUMB/ELEC/LIFT/DOOR/PUBLIC/OTHER） */
    static String categoryOf(String dataPlaneCategory) {
        return switch (dataPlaneCategory == null ? "" : dataPlaneCategory) {
            case "plumbing" -> "PLUMB";
            case "electrical" -> "ELEC";
            case "elevator" -> "LIFT";
            case "door_window" -> "DOOR";
            case "public_facility" -> "PUBLIC";
            case "other" -> "OTHER";
            default -> "";
        };
    }

    /** 数据面工单状态 → 自有 {@code wo_state}（NEW/ACCEPTED/DISPATCHED/ON_SITE/DONE/REVIEWED/ARCHIVED） */
    static String woStateOf(String dataPlaneStatus) {
        return switch (dataPlaneStatus == null ? "" : dataPlaneStatus.trim().toLowerCase()) {
            case "created" -> "NEW";
            case "accepted" -> "ACCEPTED";
            case "assigned", "dispatched" -> "DISPATCHED";
            case "onsite", "on_site" -> "ON_SITE";
            case "completed" -> "DONE";
            case "reviewed" -> "REVIEWED";
            case "archived" -> "ARCHIVED";
            default -> dataPlaneStatus == null ? "" : dataPlaneStatus.toUpperCase();
        };
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private static void copy(JsonNode source, ObjectNode target, String sourceField, String targetField) {
        if (source == null) {
            return;
        }
        JsonNode value = source.get(sourceField);
        if (value == null || value.isNull()) {
            return;
        }
        target.set(targetField, value);
    }

    private static String text(JsonNode node, String field, String fallback) {
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

    private static String blockOf(JsonNode source, String roomCode) {
        String building = text(source, "building", "");
        String block = numericHead(building);
        if (!block.isBlank()) {
            return block;
        }
        int index = roomCode == null ? -1 : roomCode.indexOf('-');
        return index > 0 ? roomCode.substring(0, index) : "";
    }

    private static String unitOf(String roomCode) {
        if (roomCode == null) {
            return "1";
        }
        String[] parts = roomCode.split("-");
        return parts.length >= 3 ? parts[1] : "1";
    }

    private static String roomOf(String roomCode) {
        if (roomCode == null) {
            return "";
        }
        String[] parts = roomCode.split("-");
        return parts.length >= 3 ? parts[2] : parts[parts.length - 1];
    }

    private static String numericHead(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        int end = 0;
        while (end < trimmed.length() && Character.isDigit(trimmed.charAt(end))) {
            end++;
        }
        return end == 0 ? "" : trimmed.substring(0, end);
    }
}
