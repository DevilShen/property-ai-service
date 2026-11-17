package com.property.ai.connector.kingdee;

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
import com.property.ai.bff.dto.RoomInfo;
import com.property.ai.bff.dto.RoomSource;
import com.property.ai.bff.dto.Ticket;
import com.property.ai.bff.dto.TicketCategory;
import com.property.ai.bff.dto.TicketListItem;
import com.property.ai.bff.dto.TicketStatus;
import com.property.ai.bff.dto.TicketUrgency;
import com.property.ai.connector.Capability;
import com.property.ai.connector.ConnectorException;
import com.property.ai.connector.ErpErrorMapper;

/**
 * 金蝶 K/3 Cloud 形态（{@code services/mock-erp-kingdee} :4400）↔ 契约 DTO 的字段/字典映射
 * （P5-011 / task-62；纯函数、无 IO，可直接单测）。
 *
 * <h2>字段映射口径（README §4 差异表）</h2>
 * <ul>
 *   <li><b>房档</b>：{@code roomId/roomNo/roomName → householdId/roomCode/room(房号)}，{@code area → areaM2}，
 *       {@code ownerName → ownerNameMasked}（已脱敏 {@code 王*}），{@code outstandingTotal → arrearsAmount}；
 *       房号支持 {@code roomNo/roomId/roomName} 三种写法（读接口下推 roomNo）；</li>
 *   <li><b>账单</b>：金蝶是 <b>4 个平铺费项</b>（{@code propertyFee/waterFee/elecFee/parkingFee}），
 *       契约是 {@code items[]}。归一规则：{@code propertyFee → PROPERTY_FEE}、
 *       <b>{@code waterFee + elecFee → PUBLIC_ENERGY}</b>（对齐 NC 公摊电费口径，<b>仅展示层归一</b>，
 *       不重算金额、不改 ERP 主数据）、{@code parkingFee → PARKING_FEE}；
 *       <b>口径差异（必须如实报告，不得声称两套 ERP 等价）</b>：NC 侧 {@code PUBLIC_ENERGY=38}
 *       是一个费项，金蝶侧拆成「水费 18 + 电费 20」，合计同为 38；{@code dueDate} 金蝶当月 25 日 /
 *       NC 当月 15 日；金蝶无 {@code lateFee}、无 {@code prevTotal/diff}（不造数，留空）；</li>
 *   <li><b>工单</b>：金蝶小写状态 → 契约 7 节点：
 *       {@code pending → CREATED}、{@code dispatched → DISPATCHED}、{@code processing → ON_SITE}、
 *       {@code finished → COMPLETED}、{@code closed → ARCHIVED}（金蝶有独立「已关闭」语义，
 *       README §4 建议映射 completed 并标注，本实现按「已关闭 = 已归档」保真映射并在此注明）；
 *       金蝶 {@code type}（repair/complaint/consultation/cleaning/inspection）是<b>工单类型</b>而非报修专业分类，
 *       契约 {@link TicketCategory} 无对应取值 → 统一 {@code OTHER}（不猜测为 plumbing/elevator，
 *       精细分类待 ERP 侧字典）；金蝶无 {@code nodes} 时间轴 / {@code team} / {@code eta}，平台侧合成；</li>
 *   <li><b>核验</b>：{@code GET /owner/verify?roomNo&tail4}（与 NC 的 POST 语义不同），
 *       {@code remainingAttempts → attemptsLeft}，{@code roomNo → roomCode}。</li>
 * </ul>
 *
 * <p>未匹配字典/缺必填字段 → {@code E-5002}（映射失败，转人工，不猜测；设计 §26.2-3）。
 */
public final class KingdeeFieldMapper {

    /** 金蝶无差额解释字段：按 NC 同口径补 K-002（公摊电费口径）引用，仅作可追溯引用、不参与金额计算 */
    private static final String FALLBACK_KB_ID = "K-002";
    private static final String FALLBACK_KB_VERSION = "V1.1";
    private static final String FALLBACK_KB_TITLE = "公摊电费分摊口径";

    private KingdeeFieldMapper() {
    }

    // ------------------------------------------------------------------
    // owner.verify
    // ------------------------------------------------------------------

    public static OwnerVerifyResult mapOwnerVerify(JsonNode data, String projectName, boolean mock) {
        boolean verified = data.path("verified").asBoolean(false);
        boolean locked = data.path("locked").asBoolean(false);
        Integer attemptsLeft = data.hasNonNull("remainingAttempts") ? data.path("remainingAttempts").asInt() : null;
        String roomCode = textOr(data, "roomNo", textOr(data, "roomCode", ""));
        String ownerNameMasked = verified ? textOr(data, "ownerName", "") : "";
        return new OwnerVerifyResult(verified, roomCode, ownerNameMasked, projectName, attemptsLeft, locked, mock);
    }

    // ------------------------------------------------------------------
    // room.query
    // ------------------------------------------------------------------

    public static RoomInfo mapRoom(JsonNode data, boolean mock) {
        String dataAsOf = textOr(data, "dataAsOf", null);
        String roomNo = textOr(data, "roomNo", "");
        return new RoomInfo(
                textOr(data, "roomId", ""),
                textOr(data, "projectId", ""),
                roomNo,
                textOr(data, "building", ""),
                textOr(data, "unit", ""),
                deriveRoom(roomNo, textOr(data, "roomName", "")),
                decimalOrNull(data, "area"),
                textOr(data, "layout", null),
                textOr(data, "ownerName", ""),
                enumValue(data, "bindState", BindState::fromValue, Capability.ROOM_QUERY),
                textOr(data, "parkingNo", null),
                enumValue(data, "accountStatus", AccountStatus::fromValue, Capability.ROOM_QUERY),
                data.path("overdueDays").asInt(0),
                decimalOrNull(data, "outstandingTotal"),
                mapRelations(data.path("relations")),
                textList(data.path("tags")),
                // 金蝶替身返回 source=kingdee-mock（厂商私有值），不在契约枚举内 → 统一 mock 来源
                mock ? RoomSource.MOCK : RoomSource.ERP,
                mock,
                dataAsOf,
                textOr(data, "updatedAt", dataAsOf));
    }

    /** 房号（纯房间号，如 1-1-01 → 01）：取 roomNo 末段；缺失时从 roomName（1栋101）取数字段 */
    private static String deriveRoom(String roomNo, String roomName) {
        if (roomNo != null && !roomNo.isBlank()) {
            int index = roomNo.lastIndexOf('-');
            return index >= 0 && index < roomNo.length() - 1 ? roomNo.substring(index + 1) : roomNo;
        }
        if (roomName != null && !roomName.isBlank()) {
            String digits = roomName.replaceAll("\\D+", "");
            return digits.isEmpty() ? roomName : digits;
        }
        return "";
    }

    private static List<HouseholdRelation> mapRelations(JsonNode relations) {
        if (relations == null || !relations.isArray()) {
            return List.of();
        }
        List<HouseholdRelation> rows = new ArrayList<>();
        for (JsonNode node : relations) {
            rows.add(new HouseholdRelation(
                    enumValue(node, "relationType", RelationType::fromValue, Capability.ROOM_QUERY),
                    textOr(node, "nameMasked", ""),
                    // 金蝶已脱敏手机号只在回验通过时按尾号回传，关系人列表不含手机号 → null（不序列化）
                    textOr(node, "phoneMasked", null),
                    node.hasNonNull("isPrimary") ? node.path("isPrimary").asBoolean() : null));
        }
        return rows;
    }

    // ------------------------------------------------------------------
    // bill.query
    // ------------------------------------------------------------------

    /**
     * 金蝶账单 → 契约 {@link BillInfo}：4 个平铺费项归一为 {@code items[]}。
     *
     * <p>金额不做重算：{@code total/paid/outstanding} 全部取 ERP；{@code due} 无独立字段时取
     * {@code outstanding}（同口径「本期未缴」）。{@code waterFee + elecFee} 合并为 {@code PUBLIC_ENERGY}
     * 只为展示对齐 NC 口径，<b>合计与 ERP 一致</b>（合计恒等式由 mock 侧保证）。
     */
    public static BillInfo mapBill(JsonNode data, boolean mock) {
        BigDecimal total = decimalOrNull(data, "total");
        BigDecimal paid = decimalOrNull(data, "paid");
        BigDecimal outstanding = decimalOrNull(data, "outstanding");
        BigDecimal due = outstanding != null ? outstanding : total;
        return new BillInfo(
                textOr(data, "period", ""),
                total,
                due,
                mapFlatFeeItems(data),
                null,
                null,
                new Citation(FALLBACK_KB_ID, FALLBACK_KB_VERSION, FALLBACK_KB_TITLE, null, null),
                // 费项口径差异（水/电 ↔ 公摊电费）需要连接器归一，平台不据此生成差额解释
                "金蝶口径：水费 + 电费 = 公摊电费（仅展示层归一，合计与 ERP 一致）",
                textOr(data, "billId", null),
                textOr(data, "roomId", null),
                textOr(data, "projectId", null),
                textOr(data, "roomNo", null),
                paid,
                outstanding,
                // 金蝶侧无滞纳金字段（NC 有）：留空不造数
                null,
                enumValue(data, "status", BillStatus::fromValue, Capability.BILL_QUERY),
                textOr(data, "dueDate", null),
                textOr(data, "currency", "CNY"),
                mock ? BillSource.MOCK : BillSource.ERP,
                textOr(data, "dataAsOf", null),
                mock,
                null,
                intOrNull(data, "overdueDays"),
                null,
                null);
    }

    /**
     * 4 平铺费项 → {@code items[]}（展示层归一）。
     *
     * <p>{@code waterFee + elecFee → PUBLIC_ENERGY}（0 元时省略该行，避免出现无意义 0 费项）；
     * 其余费项为 0 时同样省略（与 NC 侧「0 元费项不出行」的展示口径一致）。合计恒等式不受影响。
     */
    private static List<BillItem> mapFlatFeeItems(JsonNode data) {
        List<BillItem> items = new ArrayList<>();
        addItem(items, decimalOrNull(data, "propertyFee"), "PROPERTY_FEE", "物业费", "K-001",
                "金蝶口径：面积 × 2.50 元/㎡·月");
        BigDecimal publicEnergy = add(decimalOrNull(data, "waterFee"), decimalOrNull(data, "elecFee"));
        addItem(items, publicEnergy, "PUBLIC_ENERGY", "公摊电费", "K-002",
                "展示层归一：金蝶 waterFee(水费) + elecFee(电费)；与 NC 公摊电费口径并列展示，不重算金额");
        addItem(items, decimalOrNull(data, "parkingFee"), "PARKING_FEE", "车位费", "K-003",
                "金蝶口径：车位月服务费");
        return List.copyOf(items);
    }

    private static void addItem(List<BillItem> items, BigDecimal amount, String itemCode, String label,
            String knowledgeId, String note) {
        if (amount == null || amount.signum() == 0) {
            return;
        }
        items.add(new BillItem(label, amount, note, itemCode, knowledgeId));
    }

    private static BigDecimal add(BigDecimal left, BigDecimal right) {
        if (left == null && right == null) {
            return null;
        }
        return (left == null ? BigDecimal.ZERO : left).add(right == null ? BigDecimal.ZERO : right);
    }

    // ------------------------------------------------------------------
    // ticket.*
    // ------------------------------------------------------------------

    public static Ticket mapTicket(JsonNode data, ErpRef erp, boolean mock) {
        TicketStatus status = mapStatus(textOr(data, "status", null));
        return new Ticket(
                textOr(data, "ticketId", ""),
                textOr(data, "projectId", ""),
                textOr(data, "roomId", ""),
                textOr(data, "roomNo", ""),
                mapCategory(textOr(data, "type", null)),
                TicketUrgency.NORMAL,
                "",
                IssueScope.UNKNOWN,
                "",
                "",
                null,
                textOr(data, "title", null),
                status,
                textOr(data, "statusText", ""),
                "",
                "",
                // 金蝶只读侧无 nodes 时间轴（仅 createdAt/updatedAt）：契约节点由平台侧合成，不伪造节点
                List.of(),
                1,
                // 金蝶工单由厂商系统创建，不是 AI 建单
                false,
                mock,
                erp,
                null,
                textOr(data, "createdAt", ""),
                textOr(data, "updatedAt", ""));
    }

    public static TicketListItem mapTicketListItem(JsonNode data, boolean mock) {
        return new TicketListItem(
                textOr(data, "ticketId", ""),
                textOr(data, "roomNo", ""),
                mapCategory(textOr(data, "type", null)),
                TicketUrgency.NORMAL,
                mapStatus(textOr(data, "status", null)),
                textOr(data, "statusText", ""),
                "",
                "",
                1,
                false,
                mock,
                textOr(data, "createdAt", ""),
                textOr(data, "updatedAt", ""));
    }

    /**
     * 金蝶小写状态 → 契约 7 节点（README §4 字典映射）。
     *
     * <p>{@code closed → ARCHIVED}（金蝶「已关闭」有独立语义；README §4 建议 completed 并标注，
     * 本实现按已归档保真映射并在类注释注明差异）。未覆盖状态 → {@code E-5002}（不猜测）。
     */
    public static TicketStatus mapStatus(String erpStatus) {
        if (erpStatus == null || erpStatus.isBlank()) {
            throw ErpErrorMapper.mapping(Capability.TICKET_STATUS, "金蝶未返回工单状态");
        }
        return switch (erpStatus.trim().toLowerCase()) {
            case "pending", "created" -> TicketStatus.CREATED;
            case "accepted" -> TicketStatus.ACCEPTED;
            case "dispatched", "assigned" -> TicketStatus.DISPATCHED;
            case "processing", "onsite", "on_site" -> TicketStatus.ON_SITE;
            case "finished", "completed" -> TicketStatus.COMPLETED;
            case "reviewed" -> TicketStatus.REVIEWED;
            case "closed", "archived" -> TicketStatus.ARCHIVED;
            default -> throw ErpErrorMapper.mapping(Capability.TICKET_STATUS,
                    "金蝶未覆盖的工单状态：" + erpStatus + "（已转人工，不猜测）");
        };
    }

    /**
     * 契约状态 → 金蝶状态（列表筛选下推）；{@code null} = 金蝶侧无等价状态，由平台侧本地过滤。
     */
    public static String kingdeeStatus(TicketStatus status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case CREATED -> "pending";
            case DISPATCHED -> "dispatched";
            case ON_SITE -> "processing";
            case COMPLETED -> "finished";
            case ARCHIVED -> "closed";
            case ACCEPTED, REVIEWED -> null;
        };
    }

    /**
     * 金蝶工单类型 → 契约报修分类。
     *
     * <p>金蝶 {@code type}（repair/complaint/consultation/cleaning/inspection）是工单业务类型，
     * 不携带「水/电/电梯/门窗」等专业分类 → 统一 {@code OTHER}（不猜测，细分待 ERP 字典）；
     * 未知类型 → {@code E-5002}。
     */
    public static TicketCategory mapCategory(String kingdeeType) {
        if (kingdeeType == null || kingdeeType.isBlank()) {
            return TicketCategory.OTHER;
        }
        return switch (kingdeeType.trim().toLowerCase()) {
            case "repair", "complaint", "consultation", "cleaning", "inspection" -> TicketCategory.OTHER;
            default -> throw ErpErrorMapper.mapping(Capability.TICKET_STATUS,
                    "金蝶未覆盖的工单类型：" + kingdeeType + "（已转人工，不猜测）");
        };
    }

    // ------------------------------------------------------------------
    // 字段字典（映射试跑数据源）
    // ------------------------------------------------------------------

    public static List<MappingFieldRow> mapFieldRows(JsonNode items) {
        if (items == null || !items.isArray()) {
            return List.of();
        }
        List<MappingFieldRow> rows = new ArrayList<>();
        for (JsonNode node : items) {
            String sourceField = textOr(node, "sourceField", null);
            String targetField = textOr(node, "targetField", null);
            if (sourceField == null || targetField == null) {
                throw ErpErrorMapper.mapping(Capability.ROOM_QUERY,
                        "金蝶字段字典缺少 sourceField/targetField");
            }
            rows.add(new MappingFieldRow(sourceField, targetField, textOr(node, "type", "string"),
                    node.path("required").asBoolean(false), buildTransform(node)));
        }
        return List.copyOf(rows);
    }

    /** 转换说明 = 金蝶 side note + 示例值（只做说明，不参与映射计算） */
    private static String buildTransform(JsonNode node) {
        String note = textOr(node, "note", null);
        String example = textOr(node, "example", null);
        StringBuilder text = new StringBuilder();
        if (note != null) {
            text.append(note);
        }
        if (example != null) {
            if (!text.isEmpty()) {
                text.append("；");
            }
            text.append("示例：").append(example);
        }
        return text.isEmpty() ? null : text.toString();
    }

    /** 字段字典行（避免在 mapper 里依赖 bff DTO 之外的投影结构） */
    public record MappingFieldRow(String sourceField, String targetField, String type, boolean required,
            String transform) {
    }

    // ------------------------------------------------------------------
    // JSON 工具（厂商私有结构 → 契约 DTO）
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

    private static Integer intOrNull(JsonNode data, String field) {
        if (data == null) {
            return null;
        }
        JsonNode value = data.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.isNumber() ? value.intValue() : null;
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

    /** 枚举映射：ERP 小写 code → 契约枚举；未匹配 → {@code E-5002}（映射失败，转人工） */
    public static <E> E enumValue(JsonNode node, String field, java.util.function.Function<String, E> parser,
            Capability capability) {
        String text = textOr(node, field, null);
        if (text == null) {
            return null;
        }
        try {
            return parser.apply(text);
        } catch (IllegalArgumentException ex) {
            throw ErpErrorMapper.mapping(capability, "金蝶字典未匹配（" + field + "=" + text + "），已转人工");
        }
    }

    /** ERP 引用（金蝶侧主键 ticketId；真实环境为金蝶单据内码） */
    public static ErpRef erpRef(String vendor, String ticketId) {
        return new ErpRef(vendor, ticketId == null ? "" : ticketId);
    }

    public static ConnectorException missingData(Capability capability, String field) {
        return ErpErrorMapper.mapping(capability, "金蝶响应缺少必需字段：" + field);
    }

    /** 字段字典 → 契约 {@code MappingField}（列表转换，供连接器一次投影） */
    public static List<com.property.ai.bff.dto.MappingField> toMappingFields(JsonNode items) {
        List<com.property.ai.bff.dto.MappingField> rows = new ArrayList<>();
        for (MappingFieldRow row : mapFieldRows(items)) {
            rows.add(new com.property.ai.bff.dto.MappingField(row.sourceField(), row.targetField(), row.type(),
                    row.required(), row.transform()));
        }
        return List.copyOf(rows);
    }

    /** 能力三态行由 {@code KingdeeErpConnector#probeCapabilities} 直接映射（需要 18 项能力目录回填） */

    /** 组织查询参数（金蝶列表排序字典：roomNo_asc|roomNo_desc|area_desc|outstanding_desc） */
    public static Map<String, String> query(String... pairs) {
        Map<String, String> query = new LinkedHashMap<>();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            if (pairs[index] != null && pairs[index + 1] != null) {
                query.put(pairs[index], pairs[index + 1]);
            }
        }
        return query;
    }
}
