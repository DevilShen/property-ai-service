package com.property.ai.connector.selfbuilt;

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
import com.property.ai.connector.nc.ErpFieldMapper;
import com.property.ai.connector.spi.ConnectorSpi;

/**
 * 自研 ERP（自有形态 3.0）连接器适配（P5-012 / task-65；**只读**，mock 测试替身）。
 *
 * <h2>定位（AGENTS.md NEVER #4/#11）</h2>
 * <ul>
 *   <li><b>测试替身，不是真实自研 ERP</b>：数据面复用已起的 mock（{@code property.erp.self-built-base-url}，
 *       默认 NC mock :4200），所有响应带 {@code mock:true}，注册表 {@code ConnectorDefinition.mock=true}，
 *       不得把 mock 说成真实 ERP 能力；</li>
 *   <li><b>只读</b>：只实现房档/业主核验/账单/工单进度（写操作一律 {@code E-5001}，由平台侧/NC 承载）；</li>
 *   <li><b>自有形态字段</b>：{@code house_block}/{@code room_no}/{@code fee_total}/{@code workorder_no} 等
 *       自有字段经 {@link SelfBuiltWireAdapter} + {@link SelfBuiltFieldMapper} 映射为契约 DTO，
 *       与 NC（{@code pk_room/buildname}）、金蝶（{@code roomNo/propertyFee}）字段名都不同；</li>
 *   <li><b>能力探测 18 项三态</b>：{@link SelfBuiltCapabilityProfile} 给出 <b>12 supported / 4 degraded /
 *       2 unsupported</b>（与 NC 18/0/0、金蝶 14/3/1 不同分布），每项带 evidence/fallback；
 *       探测时先读数据面能力目录做目录对齐与连通性证据。</li>
 * </ul>
 *
 * <h2>健康度</h2>
 * 无独立健康轮询（连接器运行时的周期探测只针对默认 NC 连接器）：{@link #health()} 取**最近一次出站调用**
 * 结果（从未调用 = {@code unknown}），避免启动时对第 3 套 ERP 增加强依赖与测试抖动。
 *
 * <h2>SDK 声明</h2>
 * {@code sdkVersion=3.0.0-mock}、{@code fieldCount=34}（自有字典 房档 10 / 业主 6 / 账单 12 / 工单 6）、
 * {@code limits{timeoutMs=3000,pageSizeMax=100,rateLimitPerMin=120}}（与 NC 5000/200/600、金蝶 5000/200/300 分列）。
 */
@Component
public class SelfBuiltErpConnector implements ConnectorSpi {

    private static final Logger log = LoggerFactory.getLogger(SelfBuiltErpConnector.class);

    /** 注册表主键（契约 {@code ConnectorSummary.connectorId}；与 shared/README §7.6 一致） */
    public static final String CONNECTOR_ID = "self-built-erp";
    /** SDK 语义化版本（自有形态 3.0 开放平台，mock 替身版本号，避免冒充真实厂商版本） */
    public static final String SDK_VERSION = "3.0.0-mock";
    /** 字段字典条目数（{@link SelfBuiltFieldMapper#FIELD_COUNT}：房档 10 / 业主 6 / 账单 12 / 工单 6） */
    public static final int FIELD_COUNT = SelfBuiltFieldMapper.FIELD_COUNT;

    /** 自研侧只读能力（写能力未实现 → E-5001；SDK 声明 = 真正的实现面，探测画像另见 profile） */
    private static final Set<Capability> SUPPORTED = EnumSet.of(
            Capability.ROOM_QUERY, Capability.OWNER_VERIFY, Capability.BILL_QUERY,
            Capability.TICKET_STATUS, Capability.TICKET_LIST);

    private static final int ERP_LIST_PAGE_SIZE = 500;
    private static final int CONTRACT_PAGE_SIZE = 100;
    private static final int MAX_INTERNAL_PAGES = 20;

    private final HttpErpClient httpErpClient;
    private final ConnectorProperties properties;
    /** 最近一次出站调用结果（无独立健康轮询；未调用 = unknown） */
    private final AtomicReference<ConnectorHealth> health;

    public SelfBuiltErpConnector(@Qualifier("selfBuiltHttpErpClient") HttpErpClient httpErpClient,
            ConnectorProperties properties) {
        this.httpErpClient = httpErpClient;
        this.properties = properties;
        this.health = new AtomicReference<>(ConnectorHealth.unknown(properties.getSelfBuiltVendorLabel(), true,
                properties.getSelfBuiltBaseUrl()));
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
        return properties.getSelfBuiltVendorLabel();
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
        // 声明口径（与 NC 5000/200/600、金蝶 5000/200/300 分列，便于横向对比）
        return new ConnectorSdkLimits(3000, Math.max(1, properties.getSelfBuiltPageSizeMax()),
                Math.max(1, properties.getSelfBuiltRateLimitPerMin()));
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.copyOf(SUPPORTED);
    }

    @Override
    public ConnectorHealth health() {
        return health.get();
    }

    /** 记录一次出站调用结果（健康度 = 最近一次调用；不额外轮询第 3 套 ERP） */
    private void recordHealth(boolean up, String errorCode, String message) {
        if (up) {
            health.set(ConnectorHealth.up(properties.getSelfBuiltVendorLabel(), true,
                    properties.getSelfBuiltBaseUrl(), 0L, ConnectorTimes.nowIso()));
            return;
        }
        health.set(ConnectorHealth.down(properties.getSelfBuiltVendorLabel(), true,
                properties.getSelfBuiltBaseUrl(), errorCode, message, ConnectorTimes.nowIso()));
    }

    // ------------------------------------------------------------------
    // 只读能力
    // ------------------------------------------------------------------

    @Override
    public OwnerVerifyResult verifyOwner(OwnerVerifyRequest request, String traceId) {
        ensureSupported(Capability.OWNER_VERIFY);
        Map<String, Object> body = new LinkedHashMap<>();
        // 出站请求体按自有形态字段名声明，由 Wire 层翻译为数据面参数（真实自研 ERP 直接吃自有字段）
        body.put("room_code", SelfBuiltWireAdapter.roomCodeParam(request.roomCode()));
        body.put("phone_tail", request.phoneTail());
        JsonNode envelope = call(Capability.OWNER_VERIFY, "POST", "/api/v1/owner/verify", Map.of(), body, traceId);
        JsonNode data = data(Capability.OWNER_VERIFY, envelope);
        return SelfBuiltFieldMapper.mapOwnerVerify(
                SelfBuiltWireAdapter.ensureSelfBuilt(data, SelfBuiltWireAdapter.Entity.OWNER_VERIFY),
                properties.getProjectName(), isMock(envelope));
    }

    @Override
    public RoomInfo queryRoom(String roomCode, String traceId) {
        ensureSupported(Capability.ROOM_QUERY);
        JsonNode envelope = call(Capability.ROOM_QUERY, "GET", "/api/v1/rooms/" + encodePath(roomCode), Map.of(),
                null, traceId);
        return mapRoom(data(Capability.ROOM_QUERY, envelope), isMock(envelope));
    }

    @Override
    public BillInfo queryBill(String roomCode, String period, String traceId) {
        ensureSupported(Capability.BILL_QUERY);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("roomCode", SelfBuiltWireAdapter.roomCodeParam(roomCode));
        query.put("period", SelfBuiltWireAdapter.periodParam(period));
        JsonNode envelope = call(Capability.BILL_QUERY, "GET", "/api/v1/bills", query, null, traceId);
        return mapBill(data(Capability.BILL_QUERY, envelope), isMock(envelope));
    }

    @Override
    public Ticket getTicket(String ticketNo, String traceId) {
        ensureSupported(Capability.TICKET_STATUS);
        JsonNode envelope = call(Capability.TICKET_STATUS, "GET", "/api/v1/tickets/" + encodePath(ticketNo),
                Map.of(), null, traceId);
        JsonNode data = data(Capability.TICKET_STATUS, envelope);
        JsonNode selfBuilt = SelfBuiltWireAdapter.ensureSelfBuilt(data, SelfBuiltWireAdapter.Entity.TICKET);
        return SelfBuiltFieldMapper.mapTicket(selfBuilt,
                ErpFieldMapper.erpRef(vendor(), ErpFieldMapper.textOr(selfBuilt, "workorder_no", ticketNo)),
                isMock(envelope));
    }

    /**
     * 工单列表：状态下推为自有形态 {@code wo_state}（{@code NEW/ACCEPTED/...}），
     * 缺省状态（CREATED/REVIEWED/ARCHIVED 为平台侧状态）不下推，由平台侧本地过滤。
     */
    @Override
    public TicketListResult listTickets(TicketStatus status, String team, int page, int pageSize, String traceId) {
        ensureSupported(Capability.TICKET_LIST);
        Map<String, String> query = new LinkedHashMap<>();
        String woState = SelfBuiltWireAdapter.woStateParam(status);
        // 数据面只认 NC 小写状态码：Wire 层把自有 wo_state 反向翻译为数据面 code
        String dataPlaneStatus = dataPlaneStatus(status);
        if (dataPlaneStatus != null) {
            query.put("status", dataPlaneStatus);
        }
        query.put("page", String.valueOf(Math.max(1, page)));
        query.put("pageSize", String.valueOf(Math.max(1, pageSize)));
        log.debug("[self-built] listTickets woState={} dataPlaneStatus={} page={} pageSize={} traceId={}", woState,
                dataPlaneStatus, page, pageSize, traceId);
        JsonNode envelope = call(Capability.TICKET_LIST, "GET", "/api/v1/tickets", query, null, traceId);
        JsonNode data = data(Capability.TICKET_LIST, envelope);
        List<TicketListItem> rows = mapTicketListItems(data.path("items"), isMock(envelope));
        if (team == null || team.isBlank()) {
            return new TicketListResult(rows, data.path("page").asInt(page), data.path("pageSize").asInt(pageSize),
                    data.path("total").asLong(0L));
        }
        // 自研 ERP 工单无班组字段：班组筛选在平台侧为「无匹配」而不是伪造成 NC 班组
        List<TicketListItem> filtered = new ArrayList<>();
        for (TicketListItem item : rows) {
            if (team.equals(item.team())) {
                filtered.add(item);
            }
        }
        return new TicketListResult(List.copyOf(filtered), page, pageSize, filtered.size());
    }

    // ------------------------------------------------------------------
    // 写能力：自研替身为只读（无写协议）→ 统一返回能力未开通（不假成功）
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
        query.put("sort", dataPlaneSort(sort));
        JsonNode envelope = call(Capability.ROOM_QUERY, "GET", "/api/v1/rooms", query, null, traceId);
        JsonNode data = data(Capability.ROOM_QUERY, envelope);
        JsonNode items = data.path("items");
        if (!items.isArray()) {
            throw ErpFieldMapper.missingData(Capability.ROOM_QUERY, "rooms.items");
        }
        List<RoomInfo> rows = new ArrayList<>();
        JsonNode selfBuiltItems = SelfBuiltWireAdapter.ensureSelfBuiltList(items,
                SelfBuiltWireAdapter.Entity.ROOM);
        for (JsonNode node : selfBuiltItems) {
            rows.add(SelfBuiltFieldMapper.mapRoom(node, isMock(envelope)));
        }
        return new RoomListResult(List.copyOf(rows), data.path("page").asInt(page),
                data.path("pageSize").asInt(pageSize), data.path("total").asInt(rows.size()));
    }

    /**
     * 账单列表：账期与分页下推数据面；{@code status}/{@code building}/{@code sort} 在「该账期全量样本」上
     * 平台侧过滤排序后分页（与金蝶连接器同口径，P5 样本量足够；真实自研 ERP 改 ERP 侧下推）。
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

    /**
     * 字段字典：连接器**声明**的自有形态字典（34 条），不依赖数据面在线（SDK {@code fieldCount} 口径）。
     *
     * <p>未知 category 返回空列表（不造数）；映射中心按空结果提示「该连接器未登记此类字段」。
     */
    @Override
    public List<MappingField> fieldDictionary(String category, String traceId) {
        ensureSupported(Capability.ROOM_QUERY);
        List<MappingField> rows = SelfBuiltFieldMapper.fields(category);
        log.debug("[self-built] fieldDictionary category={} count={} traceId={} connectorId={}",
                category == null ? "all" : category, rows.size(), traceId, CONNECTOR_ID);
        return rows;
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
        JsonNode envelope = call(Capability.TICKET_LIST, "GET", "/api/v1/tickets", query, null, traceId);
        JsonNode data = data(Capability.TICKET_LIST, envelope);
        List<TicketListItem> rows = mapTicketListItems(data.path("items"), isMock(envelope));
        return rows.size() > cap ? List.copyOf(rows.subList(0, cap)) : List.copyOf(rows);
    }

    /**
     * 能力探测：18 项三态（{@link SelfBuiltCapabilityProfile} 的 12/4/2 厂商画像 + 数据面目录对齐证据）。
     *
     * <p><b>与 NC/金蝶的差异是人为构造的对比样本</b>（测试替身口径）：探测先读数据面
     * {@code GET /api/v1/meta/capabilities} 做连通性与 18 项目录对齐，再按画像给出三态；
     * 数据面返回不足 18 项 → {@code E-5002}（探测结果不完整，不更新矩阵）。
     *
     * @param scenario mock 数据面探测场景（{@code all_supported|degraded}）；自研三态由画像决定，不随该参数变化
     */
    @Override
    public List<CapabilityState> probeCapabilities(String scenario, String traceId) {
        ensureSupported(Capability.ROOM_QUERY);
        Map<String, String> query = new LinkedHashMap<>();
        if (scenario != null && ("all_supported".equals(scenario.trim()) || "degraded".equals(scenario.trim()))) {
            // 只转发 mock 数据面认识的场景值（真实自研 ERP 应忽略该参数）
            query.put("scenario", scenario.trim());
        }
        JsonNode envelope = call(Capability.ROOM_QUERY, "GET", "/api/v1/meta/capabilities", query, null, traceId);
        JsonNode data = data(Capability.ROOM_QUERY, envelope);
        JsonNode items = data.path("capabilities");
        if (!items.isArray() || items.size() < CapabilityCode.values().length) {
            throw ErpErrorMapper.mapping(Capability.ROOM_QUERY,
                    "自研 ERP 能力目录不完整（应为 18 项，实际 " + (items.isArray() ? items.size() : 0) + "）");
        }
        Set<CapabilityCode> upstreamCodes = EnumSet.noneOf(CapabilityCode.class);
        for (JsonNode node : items) {
            String codeText = ErpFieldMapper.textOr(node, "code", null);
            if (codeText != null) {
                try {
                    upstreamCodes.add(CapabilityCode.fromValue(codeText));
                } catch (IllegalArgumentException ex) {
                    throw ErpErrorMapper.mapping(Capability.ROOM_QUERY, "自研 ERP 能力码未匹配：" + codeText);
                }
            }
        }
        String checkedAt = ConnectorTimes.nowIso();
        String upstreamVersion = ErpFieldMapper.textOr(data, "version", "3.0");
        List<CapabilityState> rows = new ArrayList<>();
        for (SelfBuiltCapabilityProfile.Entry profile : SelfBuiltCapabilityProfile.entries()) {
            CapabilityCatalog.Entry catalog = CapabilityCatalog.entry(profile.code());
            CapabilityLevel level = SelfBuiltCapabilityProfile.levelOf(profile.code());
            boolean aligned = upstreamCodes.contains(profile.code());
            String evidence = profile.evidence() + "；探测证据：自研 3.0 目录对齐="
                    + (aligned ? "命中" : "缺失") + "（vendorVersion=" + upstreamVersion + "）";
            rows.add(new CapabilityState(catalog.designCode(), profile.code(), catalog.name(), level,
                    profile.state(), checkedAt, evidence, profile.fallback()));
        }
        long degraded = rows.stream().filter(row -> row.state() == CapabilityStatus.DEGRADED).count();
        long unsupported = rows.stream().filter(row -> row.state() == CapabilityStatus.UNSUPPORTED).count();
        log.info("[self-built] 能力探测完成 connectorId={} supported={} degraded={} unsupported={} traceId={}",
                CONNECTOR_ID, rows.size() - degraded - unsupported, degraded, unsupported, traceId);
        return List.copyOf(rows);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 出站调用 + 健康度记录（只读能力；写能力未实现） */
    private JsonNode call(Capability capability, String method, String path, Map<String, String> query,
            Object body, String traceId) {
        if (!properties.isSelfBuiltEnabled()) {
            recordHealth(false, ErrorCode.E_5001.value(), "自研连接器未启用");
            throw ErpErrorMapper.unsupported(capability);
        }
        try {
            JsonNode envelope = httpErpClient.call(capability, method, path, query, body, Map.of(), traceId);
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
            throw ErpFieldMapper.missingData(capability, "data");
        }
        return data;
    }

    private boolean isMock(JsonNode envelope) {
        return envelope == null || envelope.path("mock").asBoolean(true);
    }

    private RoomInfo mapRoom(JsonNode data, boolean mock) {
        return SelfBuiltFieldMapper.mapRoom(SelfBuiltWireAdapter.ensureSelfBuilt(data,
                SelfBuiltWireAdapter.Entity.ROOM), mock);
    }

    private BillInfo mapBill(JsonNode data, boolean mock) {
        return SelfBuiltFieldMapper.mapBill(SelfBuiltWireAdapter.ensureSelfBuilt(data,
                SelfBuiltWireAdapter.Entity.BILL), mock);
    }

    private List<TicketListItem> mapTicketListItems(JsonNode items, boolean mock) {
        if (items == null || !items.isArray()) {
            return List.of();
        }
        List<TicketListItem> rows = new ArrayList<>();
        JsonNode selfBuiltItems = SelfBuiltWireAdapter.ensureSelfBuiltList(items,
                SelfBuiltWireAdapter.Entity.TICKET);
        for (JsonNode item : selfBuiltItems) {
            rows.add(SelfBuiltFieldMapper.mapTicketListItem(item, mock));
        }
        return List.copyOf(rows);
    }

    /** 契约工单状态 → 数据面（NC 小写）筛选码；平台侧状态返回 null（不下推，由平台侧过滤） */
    private static String dataPlaneStatus(TicketStatus status) {
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

    /** 分页拉取某账期账单（账期无数据 → 空列表，不造数） */
    private List<BillInfo> fetchBills(String period, int limit, String traceId) {
        List<BillInfo> rows = new ArrayList<>();
        int page = 1;
        String dataPlanePeriod = SelfBuiltWireAdapter.periodParam(period);
        while (rows.size() < limit && page <= MAX_INTERNAL_PAGES) {
            int pageSize = Math.min(ERP_LIST_PAGE_SIZE, Math.max(1, limit - rows.size()));
            Map<String, String> query = new LinkedHashMap<>();
            query.put("period", dataPlanePeriod);
            query.put("page", String.valueOf(page));
            query.put("pageSize", String.valueOf(pageSize));
            JsonNode envelope;
            try {
                envelope = call(Capability.BILL_QUERY, "GET", "/api/v1/bills", query, null, traceId);
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
            JsonNode selfBuiltItems = SelfBuiltWireAdapter.ensureSelfBuiltList(items,
                    SelfBuiltWireAdapter.Entity.BILL);
            for (JsonNode node : selfBuiltItems) {
                rows.add(SelfBuiltFieldMapper.mapBill(node, isMock(envelope)));
            }
            int total = data.path("total").asInt(rows.size());
            if (rows.size() >= total) {
                break;
            }
            page++;
        }
        return List.copyOf(rows.size() > limit ? rows.subList(0, limit) : rows);
    }

    /** 契约排序 → 数据面排序字典（数据面支持 roomCode_asc / updatedAt_desc；其余降级为房号升序） */
    private static String dataPlaneSort(RoomListSort sort) {
        return sort == RoomListSort.UPDATED_AT_DESC ? "updatedAt_desc" : "roomCode_asc";
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
        health.set(ConnectorHealth.unknown(properties.getSelfBuiltVendorLabel(), true,
                properties.getSelfBuiltBaseUrl()));
        log.debug("[self-built] health reset to unknown connectorId={}", CONNECTOR_ID);
    }
}
