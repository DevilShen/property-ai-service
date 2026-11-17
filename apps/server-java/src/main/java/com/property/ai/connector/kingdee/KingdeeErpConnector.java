package com.property.ai.connector.kingdee;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.property.ai.bff.dto.BillInfo;
import com.property.ai.bff.dto.BillListResult;
import com.property.ai.bff.dto.BillListSort;
import com.property.ai.bff.dto.BillStatus;
import com.property.ai.bff.dto.BindState;
import com.property.ai.bff.dto.CapabilityCode;
import com.property.ai.bff.dto.CapabilityLevel;
import com.property.ai.bff.dto.CapabilityState;
import com.property.ai.bff.dto.CapabilityStatus;
import com.property.ai.bff.dto.ConnectorSdkLimits;
import com.property.ai.bff.dto.ErrorCode;
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
import com.property.ai.connector.Capability;
import com.property.ai.connector.CapabilityCatalog;
import com.property.ai.connector.ConnectorException;
import com.property.ai.connector.ConnectorHealth;
import com.property.ai.connector.ConnectorProperties;
import com.property.ai.connector.ConnectorTimes;
import com.property.ai.connector.ErpConnector;
import com.property.ai.connector.ErpErrorMapper;
import com.property.ai.connector.HttpErpClient;
import com.property.ai.connector.spi.ConnectorSpi;

/**
 * 金蝶 EAS / K/3 Cloud 形态连接器适配（P5-011 / task-62；**只读**，对接
 * {@code services/mock-erp-kingdee} 测试替身 :4400）。
 *
 * <h2>定位（AGENTS.md NEVER #4/#11）</h2>
 * <ul>
 *   <li><b>测试替身，不是真实金蝶</b>：不持有真实凭据、不接客户环境；所有响应带 {@code mock:true}，
 *       注册表 {@code ConnectorDefinition.mock=true}，不得在对外材料把 mock 说成真实 ERP 能力；</li>
 *   <li><b>只读</b>：mock-erp-2 的所有写端点返回 405 {@code E-5001}；本连接器只实现读能力
 *       （房档/核验/账单/工单进度），写能力（建单/派单/完工/回访）一律 {@code E-5001} 由平台侧 NC 承载；</li>
 *   <li>能力探测 → 18 项三态（金蝶默认 <b>14 supported / 3 degraded / 1 unsupported</b>，
 *       来源 {@code GET /api/v1/meta/capabilities}；与 NC 的 all_supported 基线不同列，
 *       仅用于多连接器横向对比，<b>不代表真实厂商能力评估</b>）。</li>
 * </ul>
 *
 * <h2>字段映射口径</h2>
 * 见 {@link KingdeeFieldMapper} 类注释（4 平铺费项 → items 归一、waterFee+elecFee ↔ PUBLIC_ENERGY、
 * 小写状态 → 契约 7 节点、dueDate 25 日 vs NC 15 日等口径差异）。
 *
 * <h2>健康度</h2>
 * 无独立健康轮询（连接器运行时的周期探测只针对默认 NC 连接器）：{@link #health()} 取**最近一次出站调用**
 * 结果（从未调用 = {@code unknown}），避免启动时对第 2 套 ERP 增加强依赖与测试抖动。
 */
@Component
public class KingdeeErpConnector implements ConnectorSpi {

    private static final Logger log = LoggerFactory.getLogger(KingdeeErpConnector.class);

    /** 注册表主键（契约 {@code ConnectorSummary.connectorId}；与 shared/README §7.6 一致） */
    public static final String CONNECTOR_ID = "kingdee-eas";
    /** SDK 语义化版本（金蝶替身 {@code /api/v1/meta} version=2.1.0） */
    public static final String SDK_VERSION = "2.1.0";
    /** 字段字典条目数（替身 {@code /api/v1/meta/fields} 声明 38 条：room 11 / bill 13 / ticket 7 / owner 7） */
    public static final int FIELD_COUNT = 38;
    /** 演示项目名（金蝶连接器承载 P-JZY 金竹苑；仅为核验结果展示字段，不含真实组织信息） */
    public static final String PROJECT_NAME = "金竹苑";

    /** 金蝶侧只读能力（写能力由平台侧/NC 承载，未实现 → E-5001） */
    private static final Set<Capability> SUPPORTED = EnumSet.of(
            Capability.ROOM_QUERY, Capability.OWNER_VERIFY, Capability.BILL_QUERY,
            Capability.TICKET_STATUS, Capability.TICKET_LIST);

    private static final int ERP_LIST_PAGE_SIZE = 500;
    private static final int CONTRACT_PAGE_SIZE = 100;
    private static final int MAX_INTERNAL_PAGES = 20;

    private final HttpErpClient httpErpClient;
    private final ConnectorProperties properties;
    /** 最近一次出站调用结果（无独立健康轮询；未调用 = unknown，见类注释） */
    private final AtomicReference<ConnectorHealth> health;

    public KingdeeErpConnector(@Qualifier("kingdeeHttpErpClient") HttpErpClient httpErpClient,
            ConnectorProperties properties) {
        this.httpErpClient = httpErpClient;
        this.properties = properties;
        this.health = new AtomicReference<>(ConnectorHealth.unknown(properties.getKingdeeVendorLabel(), true,
                properties.getKingdeeBaseUrl()));
    }

    // ------------------------------------------------------------------
    // SDK / 元数据（ConnectorSpi）
    // ------------------------------------------------------------------

    @Override
    public String connectorId() {
        return CONNECTOR_ID;
    }

    @Override
    public String vendor() {
        return properties.getKingdeeVendorLabel();
    }

    @Override
    public String sdkVersion() {
        return SDK_VERSION;
    }

    @Override
    public int fieldCount() {
        return FIELD_COUNT;
    }

    @Override
    public ConnectorSdkLimits sdkLimits() {
        // 声明口径：超时 5000ms / 分页上限 / 每分钟限流（连接器运行时强制；与 NC 分列便于横向对比）
        return new ConnectorSdkLimits(5000, Math.max(1, properties.getKingdeePageSizeMax()),
                Math.max(1, properties.getKingdeeRateLimitPerMin()));
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.copyOf(SUPPORTED);
    }

    @Override
    public ConnectorHealth health() {
        return health.get();
    }

    /** 记录一次出站调用结果（健康度 = 最近一次调用；不额外轮询第 2 套 ERP，避免强依赖与测试抖动） */
    private void recordHealth(boolean up, String errorCode, String message) {
        if (up) {
            health.set(ConnectorHealth.up(properties.getKingdeeVendorLabel(), true, properties.getKingdeeBaseUrl(),
                    0L, ConnectorTimes.nowIso()));
            return;
        }
        health.set(ConnectorHealth.down(properties.getKingdeeVendorLabel(), true, properties.getKingdeeBaseUrl(),
                errorCode, message, ConnectorTimes.nowIso()));
    }

    // ------------------------------------------------------------------
    // 只读能力
    // ------------------------------------------------------------------

    @Override
    public OwnerVerifyResult verifyOwner(OwnerVerifyRequest request, String traceId) {
        ensureSupported(Capability.OWNER_VERIFY);
        Map<String, String> query = KingdeeFieldMapper.query(
                "roomNo", request.roomCode(),
                "tail4", request.phoneTail());
        JsonNode envelope = call(Capability.OWNER_VERIFY, "/api/v1/owner/verify", query, traceId);
        return KingdeeFieldMapper.mapOwnerVerify(data(Capability.OWNER_VERIFY, envelope), PROJECT_NAME,
                isMock(envelope));
    }

    @Override
    public RoomInfo queryRoom(String roomCode, String traceId) {
        ensureSupported(Capability.ROOM_QUERY);
        JsonNode envelope = call(Capability.ROOM_QUERY, "/api/v1/rooms/" + encodePath(roomCode), Map.of(), traceId);
        return KingdeeFieldMapper.mapRoom(data(Capability.ROOM_QUERY, envelope), isMock(envelope));
    }

    @Override
    public BillInfo queryBill(String roomCode, String period, String traceId) {
        ensureSupported(Capability.BILL_QUERY);
        JsonNode envelope = call(Capability.BILL_QUERY, "/api/v1/bills", KingdeeFieldMapper.query(
                "roomNo", roomCode, "period", period), traceId);
        return KingdeeFieldMapper.mapBill(data(Capability.BILL_QUERY, envelope), isMock(envelope));
    }

    @Override
    public Ticket getTicket(String ticketNo, String traceId) {
        ensureSupported(Capability.TICKET_STATUS);
        JsonNode envelope = call(Capability.TICKET_STATUS, "/api/v1/tickets/" + encodePath(ticketNo), Map.of(),
                traceId);
        JsonNode data = data(Capability.TICKET_STATUS, envelope);
        return KingdeeFieldMapper.mapTicket(data, KingdeeFieldMapper.erpRef(vendor(),
                KingdeeFieldMapper.textOr(data, "ticketId", ticketNo)), isMock(envelope));
    }

    @Override
    public TicketListResult listTickets(TicketStatus status, String team, int page, int pageSize, String traceId) {
        ensureSupported(Capability.TICKET_LIST);
        String pushDownStatus = KingdeeFieldMapper.kingdeeStatus(status);
        Map<String, String> query = new LinkedHashMap<>();
        if (pushDownStatus != null) {
            query.put("status", pushDownStatus);
        }
        query.put("page", String.valueOf(Math.max(1, page)));
        query.put("pageSize", String.valueOf(Math.max(1, pageSize)));
        JsonNode envelope = call(Capability.TICKET_LIST, "/api/v1/tickets", query, traceId);
        JsonNode data = data(Capability.TICKET_LIST, envelope);
        List<TicketListItem> rows = mapListItems(data.path("items"), isMock(envelope));
        if (team == null || team.isBlank()) {
            return new TicketListResult(rows, data.path("page").asInt(page), data.path("pageSize").asInt(pageSize),
                    data.path("total").asLong(0L));
        }
        // 金蝶工单无 team 字段：班组筛选在平台侧为「无匹配」而不是伪造成 NC 班组
        List<TicketListItem> filtered = new ArrayList<>();
        for (TicketListItem item : rows) {
            if (team.equals(item.team())) {
                filtered.add(item);
            }
        }
        return new TicketListResult(List.copyOf(filtered), page, pageSize, filtered.size());
    }

    // ------------------------------------------------------------------
    // 写能力：金蝶替身为只读（405 E-5001）→ 统一返回能力未开通（不假成功）
    // ------------------------------------------------------------------

    @Override
    public Ticket createTicket(TicketCreateRequest request, String idempotencyKey, String traceId) {
        throw ErpErrorMapper.unsupported(Capability.TICKET_CREATE);
    }

    @Override
    public Ticket transitionTicket(String ticketNo, TicketTransitionAction action, Map<String, Object> payload,
            String idempotencyKey, String traceId) {
        throw ErpErrorMapper.unsupported(Capability.TICKET_TRANSITION);
    }

    @Override
    public Ticket submitFeedback(String ticketNo, TicketFeedbackRequest request, String idempotencyKey,
            String traceId) {
        throw ErpErrorMapper.unsupported(Capability.TICKET_FEEDBACK);
    }

    // ------------------------------------------------------------------
    // 列表 / 映射试跑 / 能力探测
    // ------------------------------------------------------------------

    @Override
    public RoomListResult listRooms(String building, BindState bindState, int page, int pageSize, RoomListSort sort,
            String traceId) {
        ensureSupported(Capability.ROOM_QUERY);
        Map<String, String> query = new LinkedHashMap<>();
        if (building != null && !building.isBlank()) {
            query.put("building", building.trim());
        }
        if (bindState != null) {
            query.put("bindState", bindState.value());
        }
        query.put("page", String.valueOf(Math.max(1, page)));
        query.put("pageSize", String.valueOf(Math.max(1, pageSize)));
        query.put("sort", kingdeeSort(sort));
        JsonNode envelope = call(Capability.ROOM_QUERY, "/api/v1/rooms", query, traceId);
        JsonNode data = data(Capability.ROOM_QUERY, envelope);
        JsonNode items = data.path("items");
        if (!items.isArray()) {
            throw KingdeeFieldMapper.missingData(Capability.ROOM_QUERY, "rooms.items");
        }
        List<RoomInfo> rows = new ArrayList<>();
        for (JsonNode node : items) {
            rows.add(KingdeeFieldMapper.mapRoom(node, isMock(envelope)));
        }
        return new RoomListResult(List.copyOf(rows), data.path("page").asInt(page),
                data.path("pageSize").asInt(pageSize), data.path("total").asInt(rows.size()));
    }

    /**
     * 账单列表：账期与分页下推金蝶；{@code status}/{@code building}/{@code sort} 在「该账期全量样本」上
     * 平台侧过滤排序后分页（金蝶列表只支持账期/分页参数，P5 样本量 120 条足够；真实环境改 ERP 侧下推）。
     */
    @Override
    public BillListResult listBills(String period, BillStatus status, String building, int page, int pageSize,
            BillListSort sort, String traceId) {
        ensureSupported(Capability.BILL_QUERY);
        List<BillInfo> rows = fetchBills(period, MAX_INTERNAL_PAGES * ERP_LIST_PAGE_SIZE, traceId);
        List<BillInfo> filtered = new ArrayList<>();
        for (BillInfo bill : rows) {
            if (status != null && bill.status() != status) {
                continue;
            }
            if (!matchesBuilding(bill.roomCode(), building)) {
                continue;
            }
            filtered.add(bill);
        }
        filtered.sort(comparator(sort));
        int total = filtered.size();
        int from = Math.min(Math.max(0, (page - 1) * pageSize), total);
        int to = Math.min(from + pageSize, total);
        return new BillListResult(List.copyOf(filtered.subList(from, to)), page, pageSize, total);
    }

    @Override
    public List<MappingField> fieldDictionary(String category, String traceId) {
        ensureSupported(Capability.ROOM_QUERY);
        Map<String, String> query = new LinkedHashMap<>();
        if (category != null && !category.isBlank()) {
            query.put("category", category.trim());
        }
        JsonNode envelope = call(Capability.ROOM_QUERY, "/api/v1/meta/fields", query, traceId);
        JsonNode data = data(Capability.ROOM_QUERY, envelope);
        JsonNode items = data.path("fields");
        if (!items.isArray()) {
            throw KingdeeFieldMapper.missingData(Capability.ROOM_QUERY, "fields");
        }
        return KingdeeFieldMapper.toMappingFields(items);
    }

    @Override
    public List<RoomInfo> sampleRooms(int limit, String traceId) {
        int cap = Math.max(1, limit);
        List<RoomInfo> rows = new ArrayList<>();
        int page = 1;
        while (rows.size() < cap && page <= MAX_INTERNAL_PAGES) {
            int pageSize = Math.min(CONTRACT_PAGE_SIZE, cap - rows.size());
            RoomListResult result = listRooms(null, null, page, pageSize, RoomListSort.ROOM_CODE_ASC, traceId);
            if (result.items().isEmpty()) {
                break;
            }
            rows.addAll(result.items());
            if (rows.size() >= result.total() || result.items().size() < pageSize) {
                break;
            }
            page++;
        }
        return List.copyOf(rows.size() > cap ? rows.subList(0, cap) : rows);
    }

    @Override
    public List<BillInfo> sampleBills(String period, int limit, String traceId) {
        return fetchBills(period, Math.max(1, limit), traceId);
    }

    @Override
    public List<TicketListItem> sampleTickets(int limit, String traceId) {
        ensureSupported(Capability.TICKET_LIST);
        int cap = Math.max(1, limit);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("page", "1");
        query.put("pageSize", String.valueOf(Math.min(cap, ERP_LIST_PAGE_SIZE)));
        JsonNode envelope = call(Capability.TICKET_LIST, "/api/v1/tickets", query, traceId);
        JsonNode data = data(Capability.TICKET_LIST, envelope);
        List<TicketListItem> rows = mapListItems(data.path("items"), isMock(envelope));
        return rows.size() > cap ? List.copyOf(rows.subList(0, cap)) : List.copyOf(rows);
    }

    /**
     * 能力探测：金蝶替身 {@code GET /api/v1/meta/capabilities?scenario=}（默认 14/3/1）。
     *
     * <p>返回 18 项契约 {@link CapabilityState}（顺序 CAP-01…CAP-18），{@code scenario} 为测试替身人为构造的
     * 对比样本，生产连接器应忽略该参数并按真实返回（{@code kingdee_default} 即真实返回形态）。
     */
    @Override
    public List<CapabilityState> probeCapabilities(String scenario, String traceId) {
        ensureSupported(Capability.ROOM_QUERY);
        Map<String, String> query = new LinkedHashMap<>();
        String effectiveScenario = scenario == null || scenario.isBlank()
                ? properties.getKingdeeProbeScenario()
                : scenario.trim();
        if (effectiveScenario != null && !effectiveScenario.isBlank()) {
            query.put("scenario", effectiveScenario);
        }
        JsonNode envelope = call(Capability.ROOM_QUERY, "/api/v1/meta/capabilities", query, traceId);
        JsonNode data = data(Capability.ROOM_QUERY, envelope);
        JsonNode items = data.path("capabilities");
        if (!items.isArray() || items.isEmpty()) {
            throw KingdeeFieldMapper.missingData(Capability.ROOM_QUERY, "capabilities");
        }
        String checkedAt = ConnectorTimes.nowIso();
        List<CapabilityState> rows = new ArrayList<>();
        for (JsonNode node : items) {
            String codeText = KingdeeFieldMapper.textOr(node, "code", null);
            if (codeText == null) {
                throw KingdeeFieldMapper.missingData(Capability.ROOM_QUERY, "capabilities[].code");
            }
            CapabilityCode code;
            try {
                code = CapabilityCode.fromValue(codeText);
            } catch (IllegalArgumentException ex) {
                throw ErpErrorMapper.mapping(Capability.ROOM_QUERY, "金蝶能力码未匹配：" + codeText);
            }
            String stateText = KingdeeFieldMapper.textOr(node, "state", null);
            if (stateText == null) {
                throw ErpErrorMapper.mapping(Capability.ROOM_QUERY, "金蝶能力探测缺少三态：" + codeText);
            }
            CapabilityStatus state;
            try {
                state = CapabilityStatus.fromValue(stateText);
            } catch (IllegalArgumentException ex) {
                throw ErpErrorMapper.mapping(Capability.ROOM_QUERY,
                        "金蝶能力三态未匹配：" + codeText + "=" + stateText);
            }
            CapabilityCatalog.Entry entry = CapabilityCatalog.entry(code);
            CapabilityLevel level = entry.level();
            String levelText = KingdeeFieldMapper.textOr(node, "level", null);
            if (levelText != null) {
                try {
                    level = CapabilityLevel.fromValue(levelText);
                } catch (IllegalArgumentException ex) {
                    throw ErpErrorMapper.mapping(Capability.ROOM_QUERY,
                            "金蝶能力级别未匹配：" + codeText + "=" + levelText);
                }
            }
            rows.add(new CapabilityState(
                    KingdeeFieldMapper.textOr(node, "capabilityCode", entry.designCode()),
                    code,
                    KingdeeFieldMapper.textOr(node, "name", entry.name()),
                    level,
                    state,
                    checkedAt,
                    KingdeeFieldMapper.textOr(node, "evidence", null),
                    KingdeeFieldMapper.textOr(node, "fallback", entry.fallback())));
        }
        return List.copyOf(rows);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 出站调用 + 健康度记录（只读能力；写能力未实现） */
    private JsonNode call(Capability capability, String path, Map<String, String> query, String traceId) {
        if (!properties.isKingdeeEnabled()) {
            recordHealth(false, ErrorCode.E_5001.value(), "金蝶连接器未启用");
            throw ErpErrorMapper.unsupported(capability);
        }
        try {
            JsonNode envelope = httpErpClient.call(capability, "GET", path, query, null, Map.of(), traceId);
            recordHealth(true, null, null);
            return envelope;
        } catch (ConnectorException ex) {
            // 业务错误（E-1xxx/E-2xxx/E-3xxx）说明链路通 → 记 UP；传输/上游错误（E-400x）记 DOWN
            recordHealth(!ErpErrorMapper.retryable(ex.code()), ex.code().value(), ex.getMessage());
            throw ex;
        }
    }

    private JsonNode data(Capability capability, JsonNode envelope) {
        JsonNode data = envelope == null ? null : envelope.get("data");
        if (data == null || data.isNull() || !data.isObject()) {
            throw KingdeeFieldMapper.missingData(capability, "data");
        }
        return data;
    }

    private boolean isMock(JsonNode envelope) {
        return envelope == null || envelope.path("mock").asBoolean(true);
    }

    private List<TicketListItem> mapListItems(JsonNode items, boolean mock) {
        if (items == null || !items.isArray()) {
            return List.of();
        }
        List<TicketListItem> rows = new ArrayList<>();
        for (JsonNode item : items) {
            rows.add(KingdeeFieldMapper.mapTicketListItem(item, mock));
        }
        return List.copyOf(rows);
    }

    /**
     * 分页拉取某账期账单（金蝶替身 {@code period} 无数据返回 404 E-3001 → 列表模式返回空列表，不造数）。
     */
    private List<BillInfo> fetchBills(String period, int limit, String traceId) {
        List<BillInfo> rows = new ArrayList<>();
        int page = 1;
        while (rows.size() < limit && page <= MAX_INTERNAL_PAGES) {
            int pageSize = Math.min(ERP_LIST_PAGE_SIZE, Math.max(1, limit - rows.size()));
            Map<String, String> query = new LinkedHashMap<>();
            query.put("period", period);
            query.put("page", String.valueOf(page));
            query.put("pageSize", String.valueOf(pageSize));
            JsonNode envelope;
            try {
                envelope = call(Capability.BILL_QUERY, "/api/v1/bills", query, traceId);
            } catch (ConnectorException ex) {
                if (ex.code() == ErrorCode.E_3001) {
                    break;
                }
                throw ex;
            }
            JsonNode data = data(Capability.BILL_QUERY, envelope);
            JsonNode items = data.path("items");
            if (!items.isArray() || items.isEmpty()) {
                break;
            }
            for (JsonNode node : items) {
                rows.add(KingdeeFieldMapper.mapBill(node, isMock(envelope)));
            }
            int total = data.path("total").asInt(rows.size());
            if (rows.size() >= total) {
                break;
            }
            page++;
        }
        return List.copyOf(rows.size() > limit ? rows.subList(0, limit) : rows);
    }

    /** 契约排序 → 金蝶排序字典（金蝶无 updatedAt 排序：降级为房号升序，避免 400 E-1002） */
    private static String kingdeeSort(RoomListSort sort) {
        if (sort == RoomListSort.UPDATED_AT_DESC) {
            return "roomNo_asc";
        }
        return "roomNo_asc";
    }

    private static Comparator<BillInfo> comparator(BillListSort sort) {
        BillListSort effective = sort == null ? BillListSort.UPDATED_AT_DESC : sort;
        return switch (effective) {
            case TOTAL_DESC -> Comparator
                    .comparing(BillInfo::total, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(BillInfo::roomCode, Comparator.nullsLast(Comparator.naturalOrder()));
            case DUE_ASC -> Comparator
                    .comparing(BillInfo::dueDate, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(BillInfo::roomCode, Comparator.nullsLast(Comparator.naturalOrder()));
            case UPDATED_AT_DESC -> Comparator
                    .comparing(BillInfo::dataAsOf, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(BillInfo::roomCode, Comparator.nullsLast(Comparator.naturalOrder()));
        };
    }

    private static boolean matchesBuilding(String roomCode, String building) {
        if (building == null || building.isBlank()) {
            return true;
        }
        String expected = building.trim();
        if (expected.endsWith("栋")) {
            expected = expected.substring(0, expected.length() - 1);
        }
        if (roomCode == null || roomCode.isBlank()) {
            return false;
        }
        int index = roomCode.indexOf('-');
        String codeBuilding = index > 0 ? roomCode.substring(0, index) : roomCode;
        return expected.equals(codeBuilding);
    }

    private void ensureSupported(Capability capability) {
        if (!supports(capability)) {
            throw ErpErrorMapper.unsupported(capability);
        }
    }

    private static String encodePath(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** 诊断/测试：重置健康度到未知（不对外暴露端点） */
    public void resetHealthForTest() {
        health.set(ConnectorHealth.unknown(properties.getKingdeeVendorLabel(), true,
                properties.getKingdeeBaseUrl()));
        log.debug("[kingdee] health reset to unknown");
    }
}
