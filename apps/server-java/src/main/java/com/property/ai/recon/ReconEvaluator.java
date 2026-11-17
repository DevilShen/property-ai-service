package com.property.ai.recon;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.property.ai.bff.dto.BillInfo;
import com.property.ai.bff.dto.BillItem;
import com.property.ai.bff.dto.Citation;
import com.property.ai.bff.dto.HouseholdRelation;
import com.property.ai.bff.dto.ReconDiffItem;
import com.property.ai.bff.dto.ReconDiffKind;
import com.property.ai.bff.dto.RoomInfo;
import com.property.ai.bff.dto.TicketListItem;
import com.property.ai.connector.ConnectorException;
import com.property.ai.connector.nc.ErpFieldMapper;

/**
 * 对账判定（纯函数，无 IO，可直接单测）：ERP 原值 vs 平台展示快照 + ERP 侧口径恒等式。
 *
 * <h2>bill（账期账单，默认 200 条）逐条校验清单</h2>
 * <ol>
 *   <li><b>缺失（missing）</b>：ERP 原值缺 {@code billNo/period/total/items(非空)/roomNo}；
 *       平台快照缺契约必填 {@code period/total/due/items(非空)/cite}；</li>
 *   <li><b>金额恒等式（amount_mismatch）</b>：{@code Σitems.amount == total}、
 *       {@code total - prevTotal == diff}；</li>
 *   <li><b>逐分比对（amount_mismatch）</b>：{@code total/prevTotal/diff/paid/outstanding/lateFee}
 *       与 ERP 原值 {@link BigDecimal#compareTo} 严格相等（忽略 1.0 / 1.00 尾零，但不允许 1 分差）；</li>
 *   <li><b>due 口径</b>：mock-erp 未单列 {@code due}，平台按 {@code outstanding}（本期未缴）映射；
 *       若平台 {@code due != outstanding} 记 amount_mismatch（契约必填字段不得凭空产生）；</li>
 *   <li><b>字段漂移（field_drift）</b>：{@code billNo/roomCode/householdId/period/status/dueDate/currency}；</li>
 *   <li><b>费项明细</b>：条数、{@code itemCode/amount}（逐分）、{@code label←ERP name}、
 *       {@code knowledgeId/note}；</li>
 *   <li><b>知识引用 cite</b>：ERP 有 cite 时 {@code kbId/version} 必须一致；ERP 无 cite（差额为 0）时
 *       平台按 K-002 V1.1 补齐（映射口径，不算差异，但补齐缺失记 missing）。</li>
 * </ol>
 *
 * <h2>room（房档，样本房号取自账单样本）逐条校验清单</h2>
 * <ol>
 *   <li>ERP 必填：{@code roomCode/householdId/areaM2/bindState}；房号与 楼栋/单元/房号 三段一致；</li>
 *   <li>绑定态：{@code bound} ⟹ 手机号后四位为 4 位数字；{@code unbound} ⟹ 无后四位；</li>
 *   <li>车位：{@code parkingNo} 有 ⟺ {@code parkingCount=1}；本期账单含 {@code PARKING_FEE} 且金额 120；</li>
 *   <li>账户：{@code accountStatus=arrears} ⟹ {@code arrearsAmount>0}；</li>
 *   <li>面积口径：{@code 物业费 == round(面积×2.50, 2)}、{@code 公摊电费 == round(面积×0.38, 0)}（本期）、
 *       {@code 车位费 == 120}；</li>
 *   <li>平台快照 vs ERP 原值逐字段：{@code roomCode/householdId/projectId/building/unit/room/areaM2/layout/
 *       ownerNameMasked/bindState/parkingNo/accountStatus/overdueDays/arrearsAmount/relations/tags/source/dataAsOf}。</li>
 * </ol>
 *
 * <h2>ticket（工单列表样本）逐条校验清单</h2>
 * <ol>
 *   <li>ERP 必填：{@code ticketNo/status/statusText/createdAt}；状态必须能映射到契约 7 节点；</li>
 *   <li>平台列表必须含该工单（缺 → missing）；状态映射一致（否则 status_mismatch）；</li>
 *   <li>字段漂移：{@code roomCode(←roomNo)/team/category/urgency/statusText/etaText/createdAt/updatedAt}。</li>
 * </ol>
 *
 * <p>脱敏：差异项只带房号（保留）/单号，不带业主姓名与手机号；ERP 侧业主名本身已是掩码（如 {@code 王*}）。
 */
public final class ReconEvaluator {

    /** 平台侧 cite 补齐口径（与 {@code ErpFieldMapper} 保持一致；仅引用，不参与金额计算） */
    private static final String FALLBACK_KB_ID = "K-002";
    private static final String FALLBACK_KB_VERSION = "V1.1";

    /** 知识库费率口径（K-001 V1.0 / K-002 V1.1 / K-003 V1.0） */
    private static final BigDecimal PROPERTY_FEE_RATE = new BigDecimal("2.5");
    private static final BigDecimal PUBLIC_ENERGY_RATE = new BigDecimal("0.38");
    private static final BigDecimal PARKING_FEE = new BigDecimal("120");

    private ReconEvaluator() {
    }

    // ==================================================================
    // 账单
    // ==================================================================

    public static List<ReconDiffItem> evaluateBill(JsonNode erp, BillInfo platform) {
        List<ReconDiffItem> diffs = new ArrayList<>();
        String roomCode = firstText(erp, "roomNo", "roomCode");
        if (roomCode == null && platform != null) {
            roomCode = platform.roomCode();
        }
        String billNo = firstText(erp, "billNo");
        if (billNo == null && platform != null) {
            billNo = platform.billNo();
        }
        String key = billNo != null ? billNo : "BILL-" + (roomCode == null ? "UNKNOWN" : roomCode);
        Ctx ctx = new Ctx(key, roomCode, billNo, null);

        // ---- ERP 原值必填 ----
        requireErp(ctx, diffs, erp, "billNo");
        requireErp(ctx, diffs, erp, "period");
        requireErp(ctx, diffs, erp, "total");
        if (erp == null || !erp.path("items").isArray() || erp.path("items").isEmpty()) {
            diffs.add(ctx.item(ReconDiffKind.MISSING, "items", "≥1 条费项", "缺失/空数组"));
        }
        if (erp != null && firstText(erp, "roomNo", "roomCode") == null) {
            diffs.add(ctx.item(ReconDiffKind.MISSING, "roomNo", "房号", "缺失"));
        }

        // ---- ERP 金额恒等式（不依赖平台侧，先验事实源自洽） ----
        BigDecimal erpTotal = decimal(erp, "total");
        BigDecimal erpPrevTotal = decimal(erp, "prevTotal");
        BigDecimal erpDiff = decimal(erp, "diff");
        if (erpTotal != null && erp != null && erp.path("items").isArray() && !erp.path("items").isEmpty()) {
            BigDecimal sum = BigDecimal.ZERO;
            boolean broken = false;
            for (JsonNode item : erp.path("items")) {
                BigDecimal amount = decimal(item, "amount");
                if (amount == null) {
                    broken = true;
                    break;
                }
                sum = sum.add(amount);
            }
            if (broken) {
                diffs.add(ctx.item(ReconDiffKind.MISSING, "items[].amount", "每项金额", "存在缺失金额的费项"));
            } else if (sum.compareTo(erpTotal) != 0) {
                diffs.add(ctx.item(ReconDiffKind.AMOUNT_MISMATCH, "items.sum",
                        sum.toPlainString(), erpTotal.toPlainString()));
            }
        }
        if (erpTotal != null && erpPrevTotal != null && erpDiff != null) {
            BigDecimal expected = erpTotal.subtract(erpPrevTotal);
            if (expected.compareTo(erpDiff) != 0) {
                diffs.add(ctx.item(ReconDiffKind.AMOUNT_MISMATCH, "diff",
                        expected.toPlainString(), erpDiff.toPlainString()));
            }
        } else if (erpDiff == null && erpTotal != null) {
            diffs.add(ctx.item(ReconDiffKind.MISSING, "diff", "total-prevTotal", "缺失"));
        }

        // ---- 平台展示快照 ----
        if (platform == null) {
            diffs.add(ctx.item(ReconDiffKind.MISSING, "platform", "平台展示快照", "缺失"));
            return diffs;
        }
        if (blank(platform.period())) {
            diffs.add(ctx.item(ReconDiffKind.MISSING, "platform.period", "账期", "缺失"));
        }
        if (platform.total() == null) {
            diffs.add(ctx.item(ReconDiffKind.MISSING, "platform.total", "本期应缴合计", "缺失"));
        }
        if (platform.due() == null) {
            diffs.add(ctx.item(ReconDiffKind.MISSING, "platform.due", "本期待缴", "缺失"));
        }
        if (platform.items() == null || platform.items().isEmpty()) {
            diffs.add(ctx.item(ReconDiffKind.MISSING, "platform.items", "费项", "缺失/空数组"));
        }
        if (platform.cite() == null) {
            diffs.add(ctx.item(ReconDiffKind.MISSING, "platform.cite", "知识引用", "缺失"));
        }

        // ---- 金额逐分比对 ----
        compareAmount(ctx, diffs, "total", erpTotal, platform.total());
        compareAmount(ctx, diffs, "prevTotal", erpPrevTotal, platform.prevTotal());
        compareAmount(ctx, diffs, "diff", erpDiff, platform.diff());
        compareAmount(ctx, diffs, "paid", decimal(erp, "paid"), platform.paid());
        compareAmount(ctx, diffs, "outstanding", decimal(erp, "outstanding"), platform.outstanding());
        compareAmount(ctx, diffs, "lateFee", decimal(erp, "lateFee"), platform.lateFee());

        // due：ERP 无该字段时平台按 outstanding 映射（口径见 ErpFieldMapper）
        BigDecimal erpDue = decimal(erp, "due");
        BigDecimal expectedDue = erpDue != null ? erpDue : decimal(erp, "outstanding");
        if (expectedDue != null) {
            compareAmount(ctx, diffs, "due", expectedDue, platform.due());
        } else if (erpTotal != null && platform.paid() != null) {
            compareAmount(ctx, diffs, "due", erpTotal.subtract(platform.paid()), platform.due());
        }

        // ---- 标识/属性字段漂移 ----
        compareText(ctx, diffs, "billNo", firstText(erp, "billNo"), platform.billNo());
        compareText(ctx, diffs, "roomCode", firstText(erp, "roomNo", "roomCode"), platform.roomCode());
        compareText(ctx, diffs, "period", firstText(erp, "period"), platform.period());
        compareText(ctx, diffs, "householdId", firstText(erp, "householdId"), platform.householdId());
        compareText(ctx, diffs, "projectId", firstText(erp, "projectId"), platform.projectId());
        compareText(ctx, diffs, "status", firstText(erp, "status"),
                platform.status() == null ? null : platform.status().value());
        compareText(ctx, diffs, "dueDate", firstText(erp, "dueDate"), platform.dueDate());
        compareText(ctx, diffs, "currency", firstText(erp, "currency"), platform.currency());
        compareText(ctx, diffs, "source", firstText(erp, "source"),
                platform.source() == null ? null : platform.source().value());

        // ---- 费项明细 ----
        List<JsonNode> erpItems = array(erp, "items");
        List<BillItem> platformItems = platform.items() == null ? List.of() : platform.items();
        if (!erpItems.isEmpty() && erpItems.size() != platformItems.size()) {
            diffs.add(ctx.item(ReconDiffKind.FIELD_DRIFT, "items.length",
                    String.valueOf(erpItems.size()), String.valueOf(platformItems.size())));
        }
        int size = Math.min(erpItems.size(), platformItems.size());
        for (int i = 0; i < size; i++) {
            JsonNode raw = erpItems.get(i);
            BillItem mapped = platformItems.get(i);
            String itemCode = firstText(raw, "itemCode");
            compareText(ctx, diffs, "items[" + i + "].itemCode", itemCode, mapped.itemCode());
            compareAmount(ctx, diffs, "items[" + i + "].amount", decimal(raw, "amount"), mapped.amount());
            compareText(ctx, diffs, "items[" + i + "].label", firstText(raw, "name", "label"), mapped.label());
            compareText(ctx, diffs, "items[" + i + "].knowledgeId", firstText(raw, "knowledgeId"),
                    mapped.knowledgeId());
        }

        // ---- 知识引用 ----
        JsonNode erpCite = erp == null ? null : erp.path("cite");
        Citation cite = platform.cite();
        if (cite != null) {
            if (erpCite != null && erpCite.isObject() && !erpCite.isEmpty()) {
                compareText(ctx, diffs, "cite.kbId", firstText(erpCite, "kbId"), cite.kbId());
                compareText(ctx, diffs, "cite.version", firstText(erpCite, "version"), cite.version());
            } else if (!FALLBACK_KB_ID.equals(cite.kbId()) || !FALLBACK_KB_VERSION.equals(cite.version())) {
                diffs.add(ctx.item(ReconDiffKind.FIELD_DRIFT, "cite",
                        FALLBACK_KB_ID + " " + FALLBACK_KB_VERSION,
                        (cite.kbId() == null ? "-" : cite.kbId()) + " " + (cite.version() == null ? "-" : cite.version())));
            }
        }
        return diffs;
    }

    // ==================================================================
    // 房档
    // ==================================================================

    /**
     * @param erpRoom           ERP 房档原值
     * @param platform          平台房档展示快照
     * @param billRow           同房同账期 ERP 账单原值（用于车位/面积费用口径交叉校验，可为 null）
     * @param expectPublicEnergy 本期是否应含公摊电费（mock-erp 口径：2026-11 起按月分摊）
     */
    public static List<ReconDiffItem> evaluateRoom(JsonNode erpRoom, RoomInfo platform, JsonNode billRow,
            boolean expectPublicEnergy) {
        List<ReconDiffItem> diffs = new ArrayList<>();
        String roomCode = firstText(erpRoom, "roomCode", "roomNo");
        if (roomCode == null && platform != null) {
            roomCode = platform.roomCode();
        }
        String key = roomCode == null ? "ROOM-UNKNOWN" : roomCode;
        Ctx ctx = new Ctx(key, roomCode, null, null);

        // ---- ERP 原值必填 ----
        if (roomCode == null) {
            diffs.add(ctx.item(ReconDiffKind.MISSING, "roomCode", "房号", "缺失"));
        }
        requireErp(ctx, diffs, erpRoom, "householdId");
        BigDecimal area = decimal(erpRoom, "areaM2");
        if (area == null) {
            diffs.add(ctx.item(ReconDiffKind.MISSING, "areaM2", "面积", "缺失"));
        } else if (area.compareTo(BigDecimal.ZERO) <= 0) {
            diffs.add(ctx.item(ReconDiffKind.AMOUNT_MISMATCH, "areaM2", ">0", area.toPlainString()));
        }
        String bindState = firstText(erpRoom, "bindState");
        if (bindState == null) {
            diffs.add(ctx.item(ReconDiffKind.MISSING, "bindState", "绑定状态", "缺失"));
        }

        // ---- 房号三段一致 ----
        String building = firstText(erpRoom, "building");
        String unit = firstText(erpRoom, "unit");
        String room = firstText(erpRoom, "room");
        if (roomCode != null && building != null && unit != null && room != null) {
            String expected = digits(building) + "-" + digits(unit) + "-" + room;
            if (!expected.equals(roomCode)) {
                diffs.add(ctx.item(ReconDiffKind.FIELD_DRIFT, "roomCode", expected, roomCode));
            }
        }

        // ---- 绑定态 / 车位 / 账户 口径 ----
        String tail = firstText(erpRoom, "phoneTail4");
        if ("bound".equals(bindState) && (tail == null || !tail.matches("\\d{4}"))) {
            diffs.add(ctx.item(ReconDiffKind.FIELD_DRIFT, "phoneTail4", "4 位数字（已绑定）",
                    tail == null ? "缺失" : tail));
        }
        if ("unbound".equals(bindState) && tail != null && !tail.isBlank()) {
            diffs.add(ctx.item(ReconDiffKind.FIELD_DRIFT, "phoneTail4", "未绑定不应有后四位", tail));
        }
        String parkingNo = firstText(erpRoom, "parkingNo");
        int parkingCount = erpRoom == null ? 0 : erpRoom.path("parkingCount").asInt(0);
        if ((parkingNo != null) != (parkingCount == 1)) {
            diffs.add(ctx.item(ReconDiffKind.FIELD_DRIFT, "parkingCount",
                    parkingNo != null ? "1" : "0", String.valueOf(parkingCount)));
        }
        String accountStatus = firstText(erpRoom, "accountStatus");
        BigDecimal arrears = decimal(erpRoom, "arrearsAmount");
        if ("arrears".equals(accountStatus) && (arrears == null || arrears.compareTo(BigDecimal.ZERO) <= 0)) {
            diffs.add(ctx.item(ReconDiffKind.AMOUNT_MISMATCH, "arrearsAmount", ">0（欠费户）",
                    arrears == null ? "缺失" : arrears.toPlainString()));
        }

        // ---- 面积 × 费率 口径（知识库 K-001/K-002/K-003） ----
        if (billRow != null) {
            String billRoom = firstText(billRow, "roomNo", "roomCode");
            if (roomCode != null && billRoom != null && !roomCode.equals(billRoom)) {
                diffs.add(ctx.item(ReconDiffKind.FIELD_DRIFT, "bill.roomNo", roomCode, billRoom));
            }
            compareText(ctx, diffs, "bill.householdId", firstText(erpRoom, "householdId"),
                    firstText(billRow, "householdId"));
            List<JsonNode> items = array(billRow, "items");
            JsonNode propertyItem = findItem(items, "PROPERTY_FEE");
            JsonNode energyItem = findItem(items, "PUBLIC_ENERGY");
            JsonNode parkingItem = findItem(items, "PARKING_FEE");
            if (area != null) {
                BigDecimal expectedProperty = area.multiply(PROPERTY_FEE_RATE).setScale(2, RoundingMode.HALF_UP);
                if (propertyItem == null) {
                    diffs.add(ctx.item(ReconDiffKind.MISSING, "bill.items.PROPERTY_FEE", "物业费费项", "缺失"));
                } else {
                    compareAmount(ctx, diffs, "bill.items.PROPERTY_FEE.amount", expectedProperty,
                            decimal(propertyItem, "amount"));
                }
                if (expectPublicEnergy || energyItem != null) {
                    BigDecimal expectedEnergy = area.multiply(PUBLIC_ENERGY_RATE).setScale(0, RoundingMode.HALF_UP);
                    if (energyItem == null) {
                        diffs.add(ctx.item(ReconDiffKind.MISSING, "bill.items.PUBLIC_ENERGY", "公摊电费费项", "缺失"));
                    } else {
                        compareAmount(ctx, diffs, "bill.items.PUBLIC_ENERGY.amount", expectedEnergy,
                                decimal(energyItem, "amount"));
                    }
                }
            }
            if ((parkingNo != null) != (parkingItem != null)) {
                diffs.add(ctx.item(ReconDiffKind.FIELD_DRIFT, "bill.items.PARKING_FEE",
                        parkingNo != null ? "应含车位费" : "不应含车位费",
                        parkingItem != null ? "含" : "不含"));
            } else if (parkingItem != null) {
                compareAmount(ctx, diffs, "bill.items.PARKING_FEE.amount", PARKING_FEE,
                        decimal(parkingItem, "amount"));
            }
        }

        // ---- 平台展示快照 vs ERP 原值 ----
        if (platform == null) {
            diffs.add(ctx.item(ReconDiffKind.MISSING, "platform", "平台展示快照", "缺失"));
            return diffs;
        }
        compareText(ctx, diffs, "platform.roomCode", roomCode, platform.roomCode());
        compareText(ctx, diffs, "platform.householdId", firstText(erpRoom, "householdId"), platform.householdId());
        compareText(ctx, diffs, "platform.projectId", firstText(erpRoom, "projectId"), platform.projectId());
        compareText(ctx, diffs, "platform.building", building, platform.building());
        compareText(ctx, diffs, "platform.unit", unit, platform.unit());
        compareText(ctx, diffs, "platform.room", room, platform.room());
        compareAmount(ctx, diffs, "platform.areaM2", area, platform.areaM2());
        compareText(ctx, diffs, "platform.layout", firstText(erpRoom, "layout"), platform.layout());
        compareText(ctx, diffs, "platform.ownerNameMasked", firstText(erpRoom, "ownerNameMasked"),
                platform.ownerNameMasked());
        compareText(ctx, diffs, "platform.bindState", bindState,
                platform.bindState() == null ? null : platform.bindState().value());
        compareText(ctx, diffs, "platform.parkingNo", parkingNo, platform.parkingNo());
        compareText(ctx, diffs, "platform.accountStatus", accountStatus,
                platform.accountStatus() == null ? null : platform.accountStatus().value());
        compareAmount(ctx, diffs, "platform.overdueDays",
                erpRoom == null ? null : BigDecimal.valueOf(erpRoom.path("overdueDays").asInt(0)),
                BigDecimal.valueOf(platform.overdueDays()));
        compareAmount(ctx, diffs, "platform.arrearsAmount", arrears, platform.arrearsAmount());
        compareText(ctx, diffs, "platform.source", firstText(erpRoom, "source"),
                platform.source() == null ? null : platform.source().value());
        compareText(ctx, diffs, "platform.dataAsOf", firstText(erpRoom, "dataAsOf"), platform.dataAsOf());
        compareRelations(ctx, diffs, array(erpRoom, "relations"), platform.relations());
        compareText(ctx, diffs, "platform.tags", joinText(array(erpRoom, "tags"), "|"),
                platform.tags() == null ? null : String.join("|", platform.tags()));
        return diffs;
    }

    // ==================================================================
    // 工单
    // ==================================================================

    public static List<ReconDiffItem> evaluateTicket(JsonNode erp, TicketListItem platform) {
        List<ReconDiffItem> diffs = new ArrayList<>();
        String ticketNo = firstText(erp, "ticketNo");
        if (ticketNo == null && platform != null) {
            ticketNo = platform.ticketNo();
        }
        String roomCode = firstText(erp, "roomNo", "roomCode");
        if (roomCode == null && platform != null) {
            roomCode = platform.roomCode();
        }
        String key = ticketNo == null ? "TICKET-UNKNOWN" : ticketNo;
        Ctx ctx = new Ctx(key, roomCode, null, ticketNo);

        requireErp(ctx, diffs, erp, "ticketNo");
        String rawStatus = firstText(erp, "status");
        if (rawStatus == null) {
            diffs.add(ctx.item(ReconDiffKind.MISSING, "status", "工单状态", "缺失"));
        } else {
            try {
                ErpFieldMapper.mapStatus(rawStatus);
            } catch (ConnectorException ex) {
                diffs.add(ctx.item(ReconDiffKind.STATUS_MISMATCH, "status", "契约 7 节点内可映射状态", rawStatus));
            }
        }
        requireErp(ctx, diffs, erp, "statusText");
        requireErp(ctx, diffs, erp, "createdAt");

        if (platform == null) {
            diffs.add(ctx.item(ReconDiffKind.MISSING, "platform", "平台工单列表项", "缺失"));
            return diffs;
        }
        if (blank(platform.ticketNo())) {
            diffs.add(ctx.item(ReconDiffKind.MISSING, "platform.ticketNo", "工单号", "缺失"));
        }
        if (platform.status() == null) {
            diffs.add(ctx.item(ReconDiffKind.MISSING, "platform.status", "工单状态", "缺失"));
        } else if (rawStatus != null) {
            String expected = mapStatusValue(rawStatus);
            if (expected != null && !expected.equals(platform.status().value())) {
                diffs.add(ctx.item(ReconDiffKind.STATUS_MISMATCH, "status", expected,
                        platform.status().value()));
            }
        }
        if (blank(platform.statusText())) {
            diffs.add(ctx.item(ReconDiffKind.MISSING, "platform.statusText", "状态文案", "缺失"));
        }
        compareText(ctx, diffs, "ticketNo", ticketNo, platform.ticketNo());
        compareText(ctx, diffs, "roomCode", roomCode, platform.roomCode());
        compareText(ctx, diffs, "team", firstText(erp, "team"), platform.team());
        compareText(ctx, diffs, "category", firstText(erp, "category"),
                platform.category() == null ? null : platform.category().value());
        compareText(ctx, diffs, "urgency", firstText(erp, "urgency"),
                platform.urgency() == null ? null : platform.urgency().value());
        compareText(ctx, diffs, "statusText", firstText(erp, "statusText"), platform.statusText());
        compareText(ctx, diffs, "eta", firstText(erp, "etaText", "eta"), platform.eta());
        compareText(ctx, diffs, "createdAt", firstText(erp, "createdAt"), platform.createdAt());
        compareText(ctx, diffs, "updatedAt", firstText(erp, "updatedAt"), platform.updatedAt());
        return diffs;
    }

    // ==================================================================
    // 判定工具
    // ==================================================================

    /** 差异项上下文（key/房号/账单号/工单号；均为脱敏后的引用，不含业主姓名/手机号） */
    private record Ctx(String key, String roomCode, String billNo, String ticketNo) {

        ReconDiffItem item(ReconDiffKind kind, String field, String expected, String actual) {
            return new ReconDiffItem(key, kind, field, expected, actual, roomCode, billNo, ticketNo);
        }
    }

    private static void requireErp(Ctx ctx, List<ReconDiffItem> diffs, JsonNode erp, String field) {
        if (firstText(erp, field) == null) {
            diffs.add(ctx.item(ReconDiffKind.MISSING, field, "必填", "缺失"));
        }
    }

    private static void compareAmount(Ctx ctx, List<ReconDiffItem> diffs, String field, BigDecimal expected,
            BigDecimal actual) {
        if (expected == null || actual == null) {
            if (expected == null && actual != null) {
                diffs.add(ctx.item(ReconDiffKind.MISSING, field, "ERP 原值", actual.toPlainString()));
            } else if (expected != null) {
                diffs.add(ctx.item(ReconDiffKind.MISSING, field, expected.toPlainString(), "平台缺失"));
            }
            return;
        }
        if (expected.compareTo(actual) != 0) {
            diffs.add(ctx.item(ReconDiffKind.AMOUNT_MISMATCH, field, expected.toPlainString(),
                    actual.toPlainString()));
        }
    }

    private static void compareText(Ctx ctx, List<ReconDiffItem> diffs, String field, String expected,
            String actual) {
        String left = expected == null ? "" : expected.trim();
        String right = actual == null ? "" : actual.trim();
        if (!left.equals(right)) {
            diffs.add(ctx.item(ReconDiffKind.FIELD_DRIFT, field, left, right));
        }
    }

    private static void compareRelations(Ctx ctx, List<ReconDiffItem> diffs, List<JsonNode> erpRelations,
            List<HouseholdRelation> platformRelations) {
        List<HouseholdRelation> mapped = platformRelations == null ? List.of() : platformRelations;
        if (erpRelations.size() != mapped.size()) {
            diffs.add(ctx.item(ReconDiffKind.FIELD_DRIFT, "platform.relations.length",
                    String.valueOf(erpRelations.size()), String.valueOf(mapped.size())));
        }
        int size = Math.min(erpRelations.size(), mapped.size());
        for (int i = 0; i < size; i++) {
            JsonNode raw = erpRelations.get(i);
            HouseholdRelation relation = mapped.get(i);
            String type = firstText(raw, "relationType");
            if (type != null && (relation.relationType() == null || !type.equals(relation.relationType().value()))) {
                diffs.add(ctx.item(ReconDiffKind.FIELD_DRIFT, "platform.relations[" + i + "].type", type,
                        relation.relationType() == null ? "" : relation.relationType().value()));
            }
            compareText(ctx, diffs, "platform.relations[" + i + "].nameMasked",
                    firstText(raw, "nameMasked"), relation.nameMasked());
        }
    }

    private static String mapStatusValue(String rawStatus) {
        try {
            return ErpFieldMapper.mapStatus(rawStatus).value();
        } catch (ConnectorException ex) {
            return null;
        }
    }

    private static JsonNode findItem(List<JsonNode> items, String itemCode) {
        for (JsonNode item : items) {
            if (Objects.equals(itemCode, firstText(item, "itemCode"))) {
                return item;
            }
        }
        return null;
    }

    private static List<JsonNode> array(JsonNode node, String field) {
        List<JsonNode> rows = new ArrayList<>();
        if (node == null) {
            return rows;
        }
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            return rows;
        }
        value.forEach(rows::add);
        return rows;
    }

    private static String joinText(List<JsonNode> nodes, String separator) {
        List<String> texts = new ArrayList<>();
        for (JsonNode node : nodes) {
            if (node != null && !node.isNull()) {
                texts.add(node.asText());
            }
        }
        return String.join(separator, texts);
    }

    private static String digits(String value) {
        return value == null ? "?" : value.replaceAll("[^0-9]", "");
    }

    private static String firstText(JsonNode node, String... fields) {
        if (node == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull()) {
                String text = value.asText();
                if (text != null && !text.isEmpty()) {
                    return text;
                }
            }
        }
        return null;
    }

    /** 定点数解析：非法数值返回 null（由缺失/漂移判定接手，不抛异常中断整轮对账） */
    private static BigDecimal decimal(JsonNode node, String field) {
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
            return null;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
