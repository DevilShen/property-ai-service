package com.property.ai.connector.selfbuilt;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.property.ai.bff.dto.BillInfo;
import com.property.ai.bff.dto.BillStatus;
import com.property.ai.bff.dto.BindState;
import com.property.ai.bff.dto.MappingField;
import com.property.ai.bff.dto.OwnerVerifyResult;
import com.property.ai.bff.dto.RoomInfo;
import com.property.ai.bff.dto.Ticket;
import com.property.ai.bff.dto.TicketListItem;
import com.property.ai.bff.dto.TicketStatus;
import com.property.ai.connector.Capability;
import com.property.ai.connector.ErpErrorMapper;
import com.property.ai.connector.nc.ErpFieldMapper;

/**
 * 自研 ERP（{@code self-built-erp}，自有形态 3.0）↔ 契约 DTO 的字段映射与字典转换（P5-012 / task-65）。
 *
 * <h2>为什么要多一层「自有形态」</h2>
 * 第 3 套连接器代表**客户自研/私有化物业 ERP**（不是用友 NC、也不是金蝶 EAS），其开放协议字段命名与两家厂商
 * 都不同：楼栋 {@code house_block}、房号 {@code room_no}、费用合计 {@code fee_total}、工单号 {@code workorder_no}……
 * 本仓库内它仍是 **mock 测试替身**（AGENTS.md NEVER #4/#11，不接真实 ERP），因此数据面复用已起的 mock，
 * 由 {@link SelfBuiltWireAdapter} 做「mock 数据面 → 自有形态 wire」的协议模拟；本类只做
 * 「**自有形态 → 契约 DTO**」单向映射（真实接入时 Wire 层换成自研 ERP HTTP，本类与契约都不用改）。
 *
 * <h2>映射口径（与 NC / 金蝶的差异，全部是显式转换，不做猜测）</h2>
 * <ul>
 *   <li>房档：{@code house_block + unit_no + room_no} 组合成契约 {@code roomCode}（{@code 3-2-1802} 口径）；
 *       {@code area_sqm} → {@code areaM2}；{@code bind_flag=Y/N} → {@code bound/unbound}；</li>
 *   <li>账单：{@code fee_total/fee_paid/fee_unpaid} → {@code total/paid/outstanding}，{@code bill_period} 支持
 *       {@code 202611}（6 位）与 {@code 2026-11} 两种写法；{@code bill_state} 大小写不敏感；
 *       {@code items[].fee_code/fee_name/fee_amount} → {@code items[].itemCode/name/amount}；</li>
 *   <li>工单：{@code workorder_no} → {@code ticketNo}；自有状态机 {@code NEW/ACCEPTED/DISPATCHED/ON_SITE/DONE/
 *       REVIEWED/ARCHIVED} → 契约 7 节点；未覆盖状态 → {@code E-5002}（不猜测，转人工）；</li>
 *   <li>核验：{@code verify_result(boolean)/lock_flag} → {@code verified/locked}，{@code attempts_left} →
 *       {@code attemptsLeft}。</li>
 * </ul>
 *
 * <h2>字段字典（SDK {@code fieldCount} 的数据源）</h2>
 * {@link #FIELD_DICTIONARY} 是连接器**声明**的自有字段字典（34 条：房档 10 / 业主 6 / 账单 12 / 工单 6），
 * 不依赖数据面在线（{@code ConnectorSpi.fieldCount} 口径）。字段名与 NC（{@code pk_room/buildname}）、
 * 金蝶（{@code roomNo/propertyFee}）都不同，体现「自有形态」。
 *
 * <p>字典/状态未匹配 → {@code E-5002}（映射失败转人工，不猜测）；金额只做类型转换不做重算。
 */
public final class SelfBuiltFieldMapper {

    /** 自有形态字段字典条目数（契约 {@code ConnectorSdk.fieldCount}；房档 10 / 业主 6 / 账单 12 / 工单 6） */
    public static final int FIELD_COUNT = 34;

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /** 字段字典（顺序固定：room → owner → bill → ticket；SDK 声明与 /mappings 试跑共用） */
    private static final List<FieldDef> FIELD_DEFS = List.of(
            // ---- 房档（10）----
            def("room", "house_key", "householdId", "string", true, "自有房产主键（HOUSE-xxx）"),
            def("room", "project_key", "projectId", "string", true, "项目编码（self 口径 P-xxx）"),
            def("room", "house_block", "building", "string", true, "楼栋（如 3 / 3栋），与 room_no 组合成 roomCode"),
            def("room", "unit_no", "unit", "string", false, "单元（缺省按 1 处理，不猜测入户）"),
            def("room", "room_no", "room", "string", true, "房号（如 1802）"),
            def("room", "area_sqm", "areaM2", "number", true, "建筑面积（㎡，原样透传不重算）"),
            def("room", "layout_type", "layout", "string", false, "户型文案"),
            def("room", "bind_flag", "bindState", "enum", true, "Y/N → bound/unbound"),
            def("room", "park_space", "parkingNo", "string", false, "车位号；空 = 无车位"),
            def("room", "acct_state", "accountStatus", "enum", false, "NORMAL/ARREARS/PREPAID/UNVERIFIED"),
            // ---- 业主（6）----
            def("owner", "owner_name", "ownerNameMasked", "string", true, "已脱敏姓名（ERP 侧脱敏）"),
            def("owner", "phone_tail", "phoneTail4", "string", true, "手机号后四位（仅核验，不落日志）"),
            def("owner", "rel_type", "relations[].relationType", "enum", false, "关系人类型 owner/family/tenant"),
            def("owner", "name_masked", "relations[].nameMasked", "string", false, "关系人脱敏名"),
            def("owner", "phone_masked", "relations[].phoneMasked", "string", false, "关系人脱敏电话"),
            def("owner", "primary_flag", "relations[].isPrimary", "boolean", false, "是否主关系人"),
            // ---- 账单（12）----
            def("bill", "bill_key", "billNo", "string", true, "自有账单号（SB-BILL-yyyymm-xxxx）"),
            def("bill", "bill_period", "period", "string", true, "账期 202611 / 2026-11 两种写法"),
            def("bill", "fee_total", "total", "number", true, "应缴合计（原样透传）"),
            def("bill", "fee_paid", "paid", "number", true, "实缴"),
            def("bill", "fee_unpaid", "outstanding", "number", true, "欠费"),
            def("bill", "fee_due", "due", "number", false, "本期待缴（缺省取 outstanding）"),
            def("bill", "prev_fee_total", "prevTotal", "number", false, "上期应缴"),
            def("bill", "fee_diff", "diff", "number", false, "差额"),
            def("bill", "late_fee", "lateFee", "number", false, "滞纳金"),
            def("bill", "bill_state", "status", "enum", true, "PAID/PARTIAL/UNPAID/OVERDUE/VOID（大小写不敏感）"),
            def("bill", "due_date", "dueDate", "date", false, "应缴截止日"),
            def("bill", "currency_code", "currency", "string", false, "币种，缺省 CNY"),
            // ---- 工单（6）----
            def("ticket", "workorder_no", "ticketNo", "string", true, "自有工单号（SB-WO-xxx）"),
            def("ticket", "wo_category", "category", "enum", true, "PLUMB/ELEC/LIFT/DOOR/PUBLIC/OTHER"),
            def("ticket", "wo_urgency", "urgency", "enum", false, "NORMAL/URGENT/EMERGENCY"),
            def("ticket", "wo_state", "status", "enum", true, "NEW/ACCEPTED/DISPATCHED/ON_SITE/DONE/REVIEWED/ARCHIVED"),
            def("ticket", "symptom_text", "symptom", "string", false, "现象描述"),
            def("ticket", "report_at", "createdAt", "datetime", false, "报修时间"));

    private SelfBuiltFieldMapper() {
    }

    /** 自有字段字典全量（{@code category=all}） */
    public static List<MappingField> fields() {
        List<MappingField> rows = new ArrayList<>(FIELD_DEFS.size());
        for (FieldDef definition : FIELD_DEFS) {
            rows.add(definition.field());
        }
        return List.copyOf(rows);
    }

    /**
     * 按类别取字段字典（room|owner|bill|ticket|all；未知类别 → 空列表，由调用方按空返回而非造数）。
     *
     * <p>类别归属在字典里显式声明（不做 targetField 前缀推断，避免 {@code status} 等重名字段串类）。
     */
    public static List<MappingField> fields(String category) {
        if (category == null || category.isBlank() || "all".equalsIgnoreCase(category.trim())) {
            return fields();
        }
        String normalized = category.trim().toLowerCase();
        List<MappingField> rows = new ArrayList<>();
        for (FieldDef definition : FIELD_DEFS) {
            if (normalized.equals(definition.category())) {
                rows.add(definition.field());
            }
        }
        return List.copyOf(rows);
    }

    private static FieldDef def(String category, String source, String target, String type, boolean required,
            String transform) {
        return new FieldDef(category, new MappingField(source, target, type, required, transform));
    }

    /** 字典条目（类别 + 契约 {@link MappingField}） */
    private record FieldDef(String category, MappingField field) {
    }

    // ==================================================================
    // 自有形态 → 契约 DTO
    // ==================================================================

    /** 房档：自有形态 → {@link RoomInfo}（roomCode = house_block-unit_no-room_no） */
    public static RoomInfo mapRoom(JsonNode selfBuilt, boolean mock) {
        if (selfBuilt == null || !selfBuilt.isObject()) {
            throw ErpErrorMapper.mapping(Capability.ROOM_QUERY, "自研 ERP 房档响应缺少 data");
        }
        String roomCode = roomCode(selfBuilt);
        ObjectNode canonical = NODES.objectNode();
        canonical.put("roomCode", roomCode);
        canonical.put("householdId", ErpFieldMapper.textOr(selfBuilt, "house_key", roomCode));
        canonical.put("projectId", ErpFieldMapper.textOr(selfBuilt, "project_key", ""));
        canonical.put("building", buildingText(selfBuilt));
        canonical.put("unit", unitText(selfBuilt));
        canonical.put("room", ErpFieldMapper.textOr(selfBuilt, "room_no", ""));
        canonical.set("areaM2", selfBuilt.get("area_sqm"));
        canonical.put("layout", ErpFieldMapper.textOr(selfBuilt, "layout_type", null));
        canonical.put("ownerNameMasked", ErpFieldMapper.textOr(selfBuilt, "owner_name", ""));
        canonical.put("bindState", bindState(selfBuilt));
        canonical.put("parkingNo", ErpFieldMapper.textOr(selfBuilt, "park_space", null));
        canonical.put("accountStatus", accountStatus(selfBuilt));
        canonical.put("overdueDays", selfBuilt.path("overdue_days").asInt(0));
        canonical.set("arrearsAmount", selfBuilt.get("arrears_amount"));
        canonical.set("relations", relations(selfBuilt.path("rel_list")));
        canonical.set("tags", selfBuilt.path("tag_list").isArray() ? selfBuilt.path("tag_list") : NODES.arrayNode());
        canonical.put("source", "mock");
        canonical.put("dataAsOf", ErpFieldMapper.textOr(selfBuilt, "data_as_of", null));
        canonical.put("updatedAt", ErpFieldMapper.textOr(selfBuilt, "updated_at",
                ErpFieldMapper.textOr(selfBuilt, "data_as_of", null)));
        return ErpFieldMapper.mapRoom(canonical, mock);
    }

    /** 账单：自有形态 → {@link BillInfo}（fee_total/fee_paid/fee_unpaid ↔ total/paid/outstanding） */
    public static BillInfo mapBill(JsonNode selfBuilt, boolean mock) {
        if (selfBuilt == null || !selfBuilt.isObject()) {
            throw ErpErrorMapper.mapping(Capability.BILL_QUERY, "自研 ERP 账单响应缺少 data");
        }
        ObjectNode canonical = NODES.objectNode();
        canonical.put("period", period(selfBuilt));
        canonical.set("total", decimalNode(selfBuilt, "fee_total", Capability.BILL_QUERY));
        canonical.set("paid", decimalNode(selfBuilt, "fee_paid", Capability.BILL_QUERY));
        canonical.set("outstanding", decimalNode(selfBuilt, "fee_unpaid", Capability.BILL_QUERY));
        canonical.set("due", decimalNode(selfBuilt, "fee_due", Capability.BILL_QUERY));
        canonical.set("prevTotal", decimalNode(selfBuilt, "prev_fee_total", Capability.BILL_QUERY));
        canonical.set("diff", decimalNode(selfBuilt, "fee_diff", Capability.BILL_QUERY));
        canonical.set("lateFee", decimalNode(selfBuilt, "late_fee", Capability.BILL_QUERY));
        canonical.put("billNo", ErpFieldMapper.textOr(selfBuilt, "bill_key", null));
        canonical.put("householdId", ErpFieldMapper.textOr(selfBuilt, "house_key", null));
        canonical.put("projectId", ErpFieldMapper.textOr(selfBuilt, "project_key", null));
        canonical.put("roomCode", ErpFieldMapper.textOr(selfBuilt, "room_code", roomCode(selfBuilt)));
        canonical.put("status", billStatus(selfBuilt).value());
        canonical.put("dueDate", ErpFieldMapper.textOr(selfBuilt, "due_date", null));
        canonical.put("currency", ErpFieldMapper.textOr(selfBuilt, "currency_code", "CNY"));
        canonical.put("source", "mock");
        canonical.put("dataAsOf", ErpFieldMapper.textOr(selfBuilt, "data_as_of", null));
        canonical.set("items", billItems(selfBuilt.path("fee_items")));
        JsonNode cite = selfBuilt.get("kb_cite");
        if (cite != null && cite.isObject()) {
            canonical.set("cite", cite);
        }
        canonical.put("diffReason", ErpFieldMapper.textOr(selfBuilt, "diff_reason", null));
        return ErpFieldMapper.mapBill(canonical, mock);
    }

    /**
     * 工单详情：自有形态 → {@link Ticket}。
     *
     * @param erpRef ERP 引用（{@code ErpRef} 由连接器按 vendor 构造）
     */
    public static Ticket mapTicket(JsonNode selfBuilt, com.property.ai.bff.dto.ErpRef erpRef, boolean mock) {
        if (selfBuilt == null || !selfBuilt.isObject()) {
            throw ErpErrorMapper.mapping(Capability.TICKET_STATUS, "自研 ERP 工单响应缺少 data");
        }
        ObjectNode canonical = canonicalTicket(selfBuilt);
        return ErpFieldMapper.mapTicket(canonical, erpRef, mock);
    }

    /** 工单列表项：自有形态 → {@link TicketListItem} */
    public static TicketListItem mapTicketListItem(JsonNode selfBuilt, boolean mock) {
        if (selfBuilt == null || !selfBuilt.isObject()) {
            throw ErpErrorMapper.mapping(Capability.TICKET_LIST, "自研 ERP 工单列表项不是对象");
        }
        return ErpFieldMapper.mapTicketListItem(canonicalTicket(selfBuilt), mock);
    }

    /** 业主核验：自有形态 → {@link OwnerVerifyResult} */
    public static OwnerVerifyResult mapOwnerVerify(JsonNode selfBuilt, String projectName, boolean mock) {
        if (selfBuilt == null || !selfBuilt.isObject()) {
            throw ErpErrorMapper.mapping(Capability.OWNER_VERIFY, "自研 ERP 核验响应缺少 data");
        }
        boolean verified = booleanValue(selfBuilt.path("verify_result"));
        boolean locked = booleanValue(selfBuilt.path("lock_flag"));
        Integer attemptsLeft = selfBuilt.hasNonNull("attempts_left") ? selfBuilt.path("attempts_left").asInt() : null;
        String roomCode = ErpFieldMapper.textOr(selfBuilt, "room_code", roomCode(selfBuilt));
        String masked = verified ? ErpFieldMapper.textOr(selfBuilt, "owner_name", "") : "";
        return new OwnerVerifyResult(verified, roomCode, masked, projectName, attemptsLeft, locked, mock);
    }

    /** 工单状态字典：自有形态 → 契约 7 节点（未覆盖 → E-5002，不猜测） */
    public static TicketStatus ticketStatus(String woState) {
        if (woState == null || woState.isBlank()) {
            throw ErpErrorMapper.mapping(Capability.TICKET_STATUS, "自研 ERP 未返回工单状态（wo_state）");
        }
        return switch (woState.trim().toUpperCase()) {
            case "NEW", "CREATED" -> TicketStatus.CREATED;
            case "ACCEPTED" -> TicketStatus.ACCEPTED;
            case "DISPATCHED", "ASSIGNED" -> TicketStatus.DISPATCHED;
            case "ON_SITE", "ONSITE" -> TicketStatus.ON_SITE;
            case "DONE", "COMPLETED" -> TicketStatus.COMPLETED;
            case "REVIEWED" -> TicketStatus.REVIEWED;
            case "ARCHIVED", "CANCELLED" -> TicketStatus.ARCHIVED;
            default -> throw ErpErrorMapper.mapping(Capability.TICKET_STATUS,
                    "自研 ERP 未覆盖的工单状态：" + woState + "（已转人工，不猜测）");
        };
    }

    /** 账单状态字典（大小写不敏感；未覆盖 → E-5002） */
    public static BillStatus billStatus(JsonNode selfBuilt) {
        String raw = ErpFieldMapper.textOr(selfBuilt, "bill_state", null);
        if (raw == null || raw.isBlank()) {
            throw ErpErrorMapper.mapping(Capability.BILL_QUERY, "自研 ERP 未返回账单状态（bill_state）");
        }
        try {
            return BillStatus.fromValue(raw.trim().toLowerCase());
        } catch (IllegalArgumentException ex) {
            throw ErpErrorMapper.mapping(Capability.BILL_QUERY,
                    "自研 ERP 账单状态字典未匹配：" + raw + "（已转人工）");
        }
    }

    // ==================================================================
    // 内部：自有形态字段 → 契约 canonical 结构（再用 NC 映射器做 DTO 装配，避免第二套 DTO 构造逻辑）
    // ==================================================================

    private static ObjectNode canonicalTicket(JsonNode selfBuilt) {
        ObjectNode canonical = NODES.objectNode();
        canonical.put("ticketNo", ErpFieldMapper.textOr(selfBuilt, "workorder_no", ""));
        canonical.put("projectId", ErpFieldMapper.textOr(selfBuilt, "project_key", ""));
        canonical.put("householdId", ErpFieldMapper.textOr(selfBuilt, "house_key", ""));
        canonical.put("roomCode", ErpFieldMapper.textOr(selfBuilt, "room_code", roomCode(selfBuilt)));
        canonical.put("category", ticketCategory(ErpFieldMapper.textOr(selfBuilt, "wo_category", null)));
        canonical.put("urgency", ticketUrgency(ErpFieldMapper.textOr(selfBuilt, "wo_urgency", "NORMAL")));
        canonical.put("location", ErpFieldMapper.textOr(selfBuilt, "site", ""));
        canonical.put("issueScope", issueScope(ErpFieldMapper.textOr(selfBuilt, "scope_flag", "NONE")));
        canonical.put("duration", ErpFieldMapper.textOr(selfBuilt, "duration_text", ""));
        canonical.put("expectTime", ErpFieldMapper.textOr(selfBuilt, "expect_slot",
                ErpFieldMapper.textOr(selfBuilt, "slot", "")));
        canonical.put("note", ErpFieldMapper.textOr(selfBuilt, "note_text", null));
        canonical.put("symptom", ErpFieldMapper.textOr(selfBuilt, "symptom_text", null));
        String woState = ErpFieldMapper.textOr(selfBuilt, "wo_state", null);
        canonical.put("status", rawTicketStatus(woState));
        canonical.put("statusText", ErpFieldMapper.textOr(selfBuilt, "state_label", ""));
        canonical.put("team", ErpFieldMapper.textOr(selfBuilt, "team_name", ""));
        canonical.put("etaText", ErpFieldMapper.textOr(selfBuilt, "eta_text", ""));
        canonical.put("version", selfBuilt.path("version_no").asInt(1));
        canonical.put("aiCreated", booleanValue(selfBuilt.path("ai_flag")));
        canonical.put("createdAt", ErpFieldMapper.textOr(selfBuilt, "report_at", ""));
        canonical.put("updatedAt", ErpFieldMapper.textOr(selfBuilt, "modify_at",
                ErpFieldMapper.textOr(selfBuilt, "report_at", "")));
        canonical.set("nodes", selfBuilt.path("wo_nodes").isArray() ? selfBuilt.path("wo_nodes") : NODES.arrayNode());
        return canonical;
    }

    /** 自有工单状态 → NC 映射器可识别的中间码（CREATED/ACCEPTED/assigned/onsite/completed/reviewed/archived） */
    private static String rawTicketStatus(String woState) {
        return switch (ticketStatus(woState)) {
            case CREATED -> "created";
            case ACCEPTED -> "accepted";
            case DISPATCHED -> "assigned";
            case ON_SITE -> "onsite";
            case COMPLETED -> "completed";
            case REVIEWED -> "reviewed";
            case ARCHIVED -> "archived";
        };
    }

    private static String ticketCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ErpErrorMapper.mapping(Capability.TICKET_STATUS, "自研 ERP 未返回工单分类（wo_category）");
        }
        return switch (raw.trim().toUpperCase()) {
            case "PLUMB", "PLUMBING" -> "plumbing";
            case "ELEC", "ELECTRICAL" -> "electrical";
            case "LIFT", "ELEVATOR" -> "elevator";
            case "DOOR", "DOOR_WINDOW" -> "door_window";
            case "PUBLIC", "PUBLIC_FACILITY" -> "public_facility";
            case "OTHER" -> "other";
            default -> throw ErpErrorMapper.mapping(Capability.TICKET_STATUS,
                    "自研 ERP 工单分类字典未匹配：" + raw + "（已转人工）");
        };
    }

    private static String ticketUrgency(String raw) {
        if (raw == null || raw.isBlank()) {
            return "normal";
        }
        return switch (raw.trim().toUpperCase()) {
            case "NORMAL" -> "normal";
            case "URGENT" -> "urgent";
            case "EMERGENCY" -> "emergency";
            default -> throw ErpErrorMapper.mapping(Capability.TICKET_STATUS,
                    "自研 ERP 紧急程度字典未匹配：" + raw + "（已转人工）");
        };
    }

    private static String issueScope(String raw) {
        if (raw == null || raw.isBlank()) {
            return "none";
        }
        return switch (raw.trim().toLowerCase()) {
            case "downstairs", "down" -> "downstairs";
            case "upstairs", "up" -> "upstairs";
            default -> "none";
        };
    }

    private static String bindState(JsonNode selfBuilt) {
        String raw = ErpFieldMapper.textOr(selfBuilt, "bind_flag", null);
        if (raw == null || raw.isBlank()) {
            return BindState.PENDING.value();
        }
        return switch (raw.trim().toUpperCase()) {
            case "Y", "YES", "TRUE", "BOUND" -> BindState.BOUND.value();
            case "N", "NO", "FALSE", "UNBOUND" -> BindState.UNBOUND.value();
            case "P", "PENDING", "?", "UNKNOWN" -> BindState.PENDING.value();
            default -> throw ErpErrorMapper.mapping(Capability.ROOM_QUERY,
                    "自研 ERP 绑定态字典未匹配（bind_flag=" + raw + "），已转人工");
        };
    }

    private static String accountStatus(JsonNode selfBuilt) {
        String raw = ErpFieldMapper.textOr(selfBuilt, "acct_state", null);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return com.property.ai.bff.dto.AccountStatus.fromValue(raw.trim().toLowerCase()).value();
        } catch (IllegalArgumentException ex) {
            throw ErpErrorMapper.mapping(Capability.ROOM_QUERY,
                    "自研 ERP 账户状态字典未匹配（acct_state=" + raw + "），已转人工");
        }
    }

    private static ArrayNode relations(JsonNode relList) {
        ArrayNode rows = NODES.arrayNode();
        if (relList == null || !relList.isArray()) {
            return rows;
        }
        for (JsonNode node : relList) {
            ObjectNode row = NODES.objectNode();
            row.put("relationType", ErpFieldMapper.textOr(node, "rel_type", "owner"));
            row.put("nameMasked", ErpFieldMapper.textOr(node, "name_masked", ""));
            row.put("phoneMasked", ErpFieldMapper.textOr(node, "phone_masked", null));
            row.put("isPrimary", booleanValue(node.path("primary_flag")));
            rows.add(row);
        }
        return rows;
    }

    private static ArrayNode billItems(JsonNode feeItems) {
        ArrayNode rows = NODES.arrayNode();
        if (feeItems == null || !feeItems.isArray()) {
            return rows;
        }
        for (JsonNode node : feeItems) {
            ObjectNode row = NODES.objectNode();
            row.put("itemCode", ErpFieldMapper.textOr(node, "fee_code", ""));
            row.put("name", ErpFieldMapper.textOr(node, "fee_name", ""));
            row.set("amount", decimalNode(node, "fee_amount", Capability.BILL_QUERY));
            row.put("knowledgeId", ErpFieldMapper.textOr(node, "kb_id", null));
            row.put("note", ErpFieldMapper.textOr(node, "fee_note", null));
            rows.add(row);
        }
        return rows;
    }

    private static JsonNode decimalNode(JsonNode node, String field, Capability capability) {
        if (node == null) {
            return NODES.nullNode();
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return NODES.nullNode();
        }
        if (value.isNumber()) {
            return value;
        }
        String text = value.asText();
        if (text == null || text.isBlank()) {
            return NODES.nullNode();
        }
        try {
            return NODES.numberNode(new BigDecimal(text.trim()));
        } catch (NumberFormatException ex) {
            throw ErpErrorMapper.mapping(capability, "金额字段不是定点数：" + field + "=" + text);
        }
    }

    /** 账期归一：{@code 202611} → {@code 2026-11}；{@code 2026-11} 原样 */
    static String period(JsonNode selfBuilt) {
        String raw = ErpFieldMapper.textOr(selfBuilt, "bill_period",
                ErpFieldMapper.textOr(selfBuilt, "period", ""));
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.matches("\\d{6}")) {
            return trimmed.substring(0, 4) + "-" + trimmed.substring(4);
        }
        return trimmed;
    }

    /** 自有房号 → 契约 {@code roomCode}：楼栋-单元-房号（缺省单元按 1） */
    static String roomCode(JsonNode selfBuilt) {
        String explicit = ErpFieldMapper.textOr(selfBuilt, "room_code", null);
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        String block = numericHead(ErpFieldMapper.textOr(selfBuilt, "house_block", ""));
        String unit = numericHead(ErpFieldMapper.textOr(selfBuilt, "unit_no", "1"));
        String room = ErpFieldMapper.textOr(selfBuilt, "room_no", "");
        if (block.isBlank() || room.isBlank()) {
            return "";
        }
        return block + "-" + (unit.isBlank() ? "1" : unit) + "-" + room;
    }

    private static String buildingText(JsonNode selfBuilt) {
        String block = numericHead(ErpFieldMapper.textOr(selfBuilt, "house_block", ""));
        return block.isBlank() ? "" : block + "栋";
    }

    private static String unitText(JsonNode selfBuilt) {
        String unit = numericHead(ErpFieldMapper.textOr(selfBuilt, "unit_no", "1"));
        return unit.isBlank() ? "1单元" : unit + "单元";
    }

    /** 取字符串里的数字头（"3栋"/"3" → "3"；"1单元" → "1"）；无数值返回空串 */
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

    private static boolean booleanValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return false;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        String text = node.asText();
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.trim().toLowerCase();
        return normalized.equals("true") || normalized.equals("y") || normalized.equals("yes") || normalized.equals("1");
    }

    /** 供测试/报告：字段字典按类别计数（room/owner/bill/ticket） */
    public static Map<String, Integer> fieldCountByCategory() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String category : List.of("room", "owner", "bill", "ticket")) {
            counts.put(category, fields(category).size());
        }
        return counts;
    }
}
