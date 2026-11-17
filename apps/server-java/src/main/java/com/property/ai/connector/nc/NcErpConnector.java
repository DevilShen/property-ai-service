package com.property.ai.connector.nc;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.context.annotation.Primary;
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
import com.property.ai.connector.ConnectorHealth;
import com.property.ai.connector.ConnectorProperties;
import com.property.ai.connector.ConnectorRuntime;
import com.property.ai.connector.ConnectorTimes;
import com.property.ai.connector.ErpConnector;
import com.property.ai.connector.ErpErrorMapper;
import com.property.ai.connector.HttpErpClient;
import com.property.ai.connector.spi.ConnectorSpi;

/**
 * 用友 NC 连接器适配（P2 第 1 周对接 {@code services/mock-erp} 测试替身，NC 形态）。
 *
 * <p>端点映射见 {@code docs/openapi/README.md} §3 迁移对照表；本类只负责：
 * <ol>
 *   <li>契约 DTO → ERP 私有载荷（{@link ErpFieldMapper}）；</li>
 *   <li>ERP 私有响应 → 契约 DTO（{@link ErpFieldMapper}）；</li>
 *   <li>能力声明 + 出站调用（超时/重试/熔断/错误码翻译由 {@link HttpErpClient} 负责）。</li>
 * </ol>
 *
 * <p>⚠️ 第 1 周 ERP 侧是**测试替身**（{@code mock-erp}），所有响应带 {@code mock:true}；
 * 切换真实 NC 只替换 {@code property.erp.base-url}/凭据与映射表，**不改对外契约**。
 *
 * <p><b>P5 W19 多连接器口径（task-62 / P5-005）</b>：本类实现 {@link ConnectorSpi} 且标注
 * {@link Primary}，是能力网关的<b>默认连接器</b>（P2—P4 既有单连接器注入语义不变）；
 * 第 2 家（金蝶 {@code kingdee-eas}）按连接器 ID 经 {@code ConnectorRouter} 路由，
 * SDK 声明（{@code GET /connectors/yonyou-nc-mock/sdk}）由 {@link #sdk()} 给出。
 */
@Component
@Primary
public class NcErpConnector implements ConnectorSpi {

    /** 连接器注册表主键（= 契约 {@code ConnectorSummary.connectorId}；P2 起沿用 vendorLabel 口径） */
    public static final String CONNECTOR_ID = "yonyou-nc-mock";
    /** SDK 语义化版本（契约 v1.3.0 示例值 {@code 1.0.0}） */
    public static final String SDK_VERSION = "1.0.0";
    /** 字段字典条目数（mock-erp {@code /api/v1/meta/fields} 声明 38 条：room 8 / owner 8 / bill 13 / ticket 9） */
    public static final int FIELD_COUNT = 38;

    private static final Set<Capability> SUPPORTED = EnumSet.allOf(Capability.class);
    private static final int LOCAL_FILTER_SAMPLE_LIMIT = 500;
    /** ERP 侧（mock-erp）列表单页上限：500；连接器内部翻页取样本时用 */
    private static final int ERP_LIST_PAGE_SIZE = 500;
    /** 契约侧 pageSize 上限 100：房档样本按契约分页口径翻页 */
    private static final int CONTRACT_PAGE_SIZE = 100;
    /** 内部翻页保护（防止契约分页参数异常时无限拉取） */
    private static final int MAX_INTERNAL_PAGES = 20;

    private final HttpErpClient httpErpClient;
    private final ConnectorProperties properties;
    private final ConnectorRuntime runtime;

    public NcErpConnector(HttpErpClient httpErpClient, ConnectorProperties properties, ConnectorRuntime runtime) {
        this.httpErpClient = httpErpClient;
        this.properties = properties;
        this.runtime = runtime;
    }

    @Override
    public String vendor() {
        return properties.getVendorLabel();
    }

    @Override
    public String connectorId() {
        // 注册表主键固定为契约示例值；vendorLabel 仅作为 ErpRef.vendor（厂商）标签，二者语义不同
        return CONNECTOR_ID;
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
        // 契约示例（README §1.6 / ConnectorSdkLimits）：超时 5000ms / 分页上限 200 / 每分钟 600 次
        return new ConnectorSdkLimits(5000, 200, 600);
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.copyOf(SUPPORTED);
    }

    @Override
    public ConnectorHealth health() {
        return runtime.health();
    }

    // ------------------------------------------------------------------
    // 只读能力
    // ------------------------------------------------------------------

    @Override
    public OwnerVerifyResult verifyOwner(OwnerVerifyRequest request, String traceId) {
        ensureSupported(Capability.OWNER_VERIFY);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("roomCode", request.roomCode());
        body.put("phoneTail", request.phoneTail());
        JsonNode envelope = httpErpClient.call(Capability.OWNER_VERIFY, "POST", "/api/v1/owner/verify",
                Map.of(), body, Map.of(), traceId);
        return ErpFieldMapper.mapOwnerVerify(data(Capability.OWNER_VERIFY, envelope),
                properties.getProjectName(), isMock(envelope));
    }

    @Override
    public RoomInfo queryRoom(String roomCode, String traceId) {
        ensureSupported(Capability.ROOM_QUERY);
        JsonNode envelope = httpErpClient.call(Capability.ROOM_QUERY, "GET",
                "/api/v1/rooms/" + encodePath(roomCode), Map.of(), null, Map.of(), traceId);
        return ErpFieldMapper.mapRoom(data(Capability.ROOM_QUERY, envelope), isMock(envelope));
    }

    @Override
    public BillInfo queryBill(String roomCode, String period, String traceId) {
        ensureSupported(Capability.BILL_QUERY);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("roomCode", roomCode);
        query.put("period", period);
        JsonNode envelope = httpErpClient.call(Capability.BILL_QUERY, "GET", "/api/v1/bills", query, null,
                Map.of(), traceId);
        return ErpFieldMapper.mapBill(data(Capability.BILL_QUERY, envelope), isMock(envelope));
    }

    @Override
    public Ticket getTicket(String ticketNo, String traceId) {
        ensureSupported(Capability.TICKET_STATUS);
        JsonNode envelope = httpErpClient.call(Capability.TICKET_STATUS, "GET",
                "/api/v1/tickets/" + encodePath(ticketNo), Map.of(), null, Map.of(), traceId);
        return mapTicket(Capability.TICKET_STATUS, envelope);
    }

    @Override
    public TicketListResult listTickets(TicketStatus status, String team, int page, int pageSize, String traceId) {
        ensureSupported(Capability.TICKET_LIST);
        String pushDownStatus = ErpFieldMapper.erpStatus(status);
        boolean localFilter = team != null && !team.isBlank();
        if (!localFilter) {
            Map<String, String> query = new LinkedHashMap<>();
            if (pushDownStatus != null) {
                query.put("status", pushDownStatus);
            }
            query.put("page", String.valueOf(page));
            query.put("pageSize", String.valueOf(pageSize));
            JsonNode envelope = httpErpClient.call(Capability.TICKET_LIST, "GET", "/api/v1/tickets", query, null,
                    Map.of(), traceId);
            JsonNode data = data(Capability.TICKET_LIST, envelope);
            return new TicketListResult(
                    mapListItems(data.path("items"), isMock(envelope)),
                    data.path("page").asInt(page),
                    data.path("pageSize").asInt(pageSize),
                    data.path("total").asLong(0L));
        }
        // 班组过滤：ERP 未提供 team 查询参数 → 拉一页足够大的样本（≤500）平台侧过滤后分页。
        // P2 第 1 周样本量（200 户/13 工单）足够；P5 起改为 ERP 侧过滤下推。
        Map<String, String> sampleQuery = new LinkedHashMap<>();
        if (pushDownStatus != null) {
            sampleQuery.put("status", pushDownStatus);
        }
        sampleQuery.put("page", "1");
        sampleQuery.put("pageSize", String.valueOf(LOCAL_FILTER_SAMPLE_LIMIT));
        JsonNode envelope = httpErpClient.call(Capability.TICKET_LIST, "GET", "/api/v1/tickets", sampleQuery, null,
                Map.of(), traceId);
        JsonNode data = data(Capability.TICKET_LIST, envelope);
        List<com.property.ai.bff.dto.TicketListItem> filtered = new ArrayList<>();
        for (com.property.ai.bff.dto.TicketListItem item : mapListItems(data.path("items"), isMock(envelope))) {
            if (team.equals(item.team())) {
                filtered.add(item);
            }
        }
        long total = filtered.size();
        int from = Math.min((page - 1) * pageSize, filtered.size());
        int to = Math.min(from + pageSize, filtered.size());
        return new TicketListResult(List.copyOf(filtered.subList(from, to)), page, pageSize, total);
    }

    // ------------------------------------------------------------------
    // 写能力（不自动重试；由客户端复用幂等键）
    // ------------------------------------------------------------------

    @Override
    public Ticket createTicket(TicketCreateRequest request, String idempotencyKey, String traceId) {
        ensureSupported(Capability.TICKET_CREATE);
        Map<String, String> headers = Map.of("Idempotency-Key", idempotencyKey);
        JsonNode envelope = httpErpClient.call(Capability.TICKET_CREATE, "POST", "/api/v1/tickets", Map.of(),
                ErpFieldMapper.toErpCreatePayload(request), headers, traceId);
        return mapTicket(Capability.TICKET_CREATE, envelope);
    }

    @Override
    public Ticket transitionTicket(String ticketNo, TicketTransitionAction action, Map<String, Object> payload,
            String idempotencyKey, String traceId) {
        ensureSupported(Capability.TICKET_TRANSITION);
        Map<String, String> headers = idempotencyHeaders(idempotencyKey);
        JsonNode envelope = httpErpClient.call(Capability.TICKET_TRANSITION, "POST",
                "/api/v1/tickets/" + encodePath(ticketNo) + "/transition", Map.of(),
                ErpFieldMapper.toErpTransitionPayload(action, payload), headers, traceId);
        return mapTicket(Capability.TICKET_TRANSITION, envelope);
    }

    @Override
    public Ticket submitFeedback(String ticketNo, TicketFeedbackRequest request, String idempotencyKey,
            String traceId) {
        ensureSupported(Capability.TICKET_FEEDBACK);
        Map<String, String> headers = idempotencyHeaders(idempotencyKey);
        JsonNode envelope = httpErpClient.call(Capability.TICKET_FEEDBACK, "POST",
                "/api/v1/tickets/" + encodePath(ticketNo) + "/feedback", Map.of(),
                ErpFieldMapper.toErpFeedbackPayload(request), headers, traceId);
        return mapTicket(Capability.TICKET_FEEDBACK, envelope);
    }

    // ------------------------------------------------------------------
    // v0.3.2 增量能力（P2-023 只读分页 / P2-024 映射试跑 / P2-025 能力探测）
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
        query.put("sort", (sort == null ? RoomListSort.ROOM_CODE_ASC : sort).value());
        JsonNode envelope = httpErpClient.call(Capability.ROOM_QUERY, "GET", "/api/v1/rooms", query, null, Map.of(),
                traceId);
        JsonNode data = data(Capability.ROOM_QUERY, envelope);
        JsonNode items = data.path("items");
        if (!items.isArray()) {
            throw ErpFieldMapper.missingData(Capability.ROOM_QUERY, "rooms.items");
        }
        List<RoomInfo> rows = new ArrayList<>();
        for (JsonNode node : items) {
            rows.add(ErpFieldMapper.mapRoom(node, isMock(envelope)));
        }
        return new RoomListResult(List.copyOf(rows), data.path("page").asInt(page),
                data.path("pageSize").asInt(pageSize), data.path("total").asInt(rows.size()));
    }

    /**
     * 账单列表（v0.3.2 列表模式）。
     *
     * <p>口径：账期（必带）与分页下推 ERP；{@code status}/{@code building}/{@code sort} 由平台侧在
     * 「该账期全量样本」上过滤/排序后分页（ERP 侧未提供这三个查询参数，P2 样本量 200/账期足够；
     * P5 起改为 ERP 侧过滤下推）。{@code building} 由 {@code roomCode} 首段（楼栋号）推导，
     * 因为 ERP 账单行不含楼栋字段（roomCode 格式为 楼栋-单元-房号，契约 §2）。
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
        JsonNode envelope = httpErpClient.call(Capability.ROOM_QUERY, "GET", "/api/v1/meta/fields", query, null,
                Map.of(), traceId);
        JsonNode data = data(Capability.ROOM_QUERY, envelope);
        JsonNode items = data.path("fields");
        if (!items.isArray()) {
            throw ErpFieldMapper.missingData(Capability.ROOM_QUERY, "fields");
        }
        List<MappingField> rows = new ArrayList<>();
        for (JsonNode node : items) {
            String sourceField = ErpFieldMapper.textOr(node, "sourceField", null);
            String targetField = ErpFieldMapper.textOr(node, "targetField", null);
            if (sourceField == null || targetField == null) {
                throw ErpFieldMapper.missingData(Capability.ROOM_QUERY, "fields[].sourceField/targetField");
            }
            String type = ErpFieldMapper.textOr(node, "type", "string");
            boolean required = node.path("required").asBoolean(false);
            String note = ErpFieldMapper.textOr(node, "note", null);
            String enumValues = enumValuesText(node.path("enumValues"));
            String transform = note == null
                    ? (enumValues == null ? null : "可选值：" + enumValues)
                    : (enumValues == null ? note : note + "（可选值：" + enumValues + "）");
            rows.add(new MappingField(sourceField, targetField, type, required, transform));
        }
        return List.copyOf(rows);
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
        query.put("pageSize", String.valueOf(Math.min(cap, LOCAL_FILTER_SAMPLE_LIMIT)));
        JsonNode envelope = httpErpClient.call(Capability.TICKET_LIST, "GET", "/api/v1/tickets", query, null,
                Map.of(), traceId);
        JsonNode data = data(Capability.TICKET_LIST, envelope);
        List<TicketListItem> rows = mapListItems(data.path("items"), isMock(envelope));
        return rows.size() > cap ? List.copyOf(rows.subList(0, cap)) : List.copyOf(rows);
    }

    @Override
    public List<CapabilityState> probeCapabilities(String scenario, String traceId) {
        ensureSupported(Capability.ROOM_QUERY);
        Map<String, String> query = new LinkedHashMap<>();
        if (scenario != null && !scenario.isBlank()) {
            query.put("scenario", scenario.trim());
        }
        JsonNode envelope = httpErpClient.call(Capability.ROOM_QUERY, "GET", "/api/v1/meta/capabilities", query, null,
                Map.of(), traceId);
        JsonNode data = data(Capability.ROOM_QUERY, envelope);
        JsonNode items = data.path("capabilities");
        if (!items.isArray() || items.isEmpty()) {
            throw ErpFieldMapper.missingData(Capability.ROOM_QUERY, "capabilities");
        }
        String checkedAt = ConnectorTimes.nowIso();
        List<CapabilityState> rows = new ArrayList<>();
        for (JsonNode node : items) {
            String codeText = ErpFieldMapper.textOr(node, "code", null);
            if (codeText == null) {
                throw ErpFieldMapper.missingData(Capability.ROOM_QUERY, "capabilities[].code");
            }
            CapabilityCode code;
            try {
                code = CapabilityCode.fromValue(codeText);
            } catch (IllegalArgumentException ex) {
                throw ErpErrorMapper.mapping(Capability.ROOM_QUERY, "ERP 能力码未匹配：" + codeText);
            }
            String stateText = ErpFieldMapper.textOr(node, "state", null);
            if (stateText == null) {
                throw ErpErrorMapper.mapping(Capability.ROOM_QUERY, "ERP 能力探测缺少三态：" + codeText);
            }
            CapabilityStatus state;
            try {
                state = CapabilityStatus.fromValue(stateText);
            } catch (IllegalArgumentException ex) {
                throw ErpErrorMapper.mapping(Capability.ROOM_QUERY,
                        "ERP 能力三态未匹配：" + codeText + "=" + stateText);
            }
            CapabilityCatalog.Entry catalogEntry = CapabilityCatalog.entry(code);
            CapabilityLevel level = catalogEntry.level();
            String levelText = ErpFieldMapper.textOr(node, "level", null);
            if (levelText != null) {
                try {
                    level = CapabilityLevel.fromValue(levelText);
                } catch (IllegalArgumentException ex) {
                    throw ErpErrorMapper.mapping(Capability.ROOM_QUERY,
                            "ERP 能力级别未匹配：" + codeText + "=" + levelText);
                }
            }
            rows.add(new CapabilityState(
                    ErpFieldMapper.textOr(node, "capabilityCode", catalogEntry.designCode()),
                    code,
                    ErpFieldMapper.textOr(node, "name", catalogEntry.name()),
                    level,
                    state,
                    checkedAt,
                    ErpFieldMapper.textOr(node, "evidence", null),
                    ErpFieldMapper.textOr(node, "fallback", catalogEntry.fallback())));
        }
        return List.copyOf(rows);
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /**
     * 分页拉取某账期账单（映射后 DTO，最多 {@code limit} 条）。
     *
     * <p>账期无数据时 ERP 返回 404 {@code E-3001}（mock-erp 口径）→ 列表模式应返回空列表而不是 404
     * （契约：列表模式账期无数据返回空列表），因此这里把 E-3001 视为空样本。
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
                envelope = httpErpClient.call(Capability.BILL_QUERY, "GET", "/api/v1/bills", query, null, Map.of(),
                        traceId);
            } catch (com.property.ai.connector.ConnectorException ex) {
                if (ex.code() == com.property.ai.bff.dto.ErrorCode.E_3001) {
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
                rows.add(ErpFieldMapper.mapBill(node, isMock(envelope)));
            }
            int total = data.path("total").asInt(rows.size());
            if (rows.size() >= total) {
                break;
            }
            page++;
        }
        return List.copyOf(rows.size() > limit ? rows.subList(0, limit) : rows);
    }

    /** 账单列表排序（契约 BillListSort；空值排最后，房号升序稳定分页） */
    private static Comparator<BillInfo> comparator(BillListSort sort) {
        BillListSort effective = sort == null ? BillListSort.UPDATED_AT_DESC : sort;
        return switch (effective) {
            case TOTAL_DESC -> Comparator
                    .comparing(BillInfo::total, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(BillInfo::roomCode, Comparator.nullsLast(Comparator.naturalOrder()));
            case DUE_ASC -> Comparator
                    .comparing(BillInfo::dueDate, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(BillInfo::roomCode, Comparator.nullsLast(Comparator.naturalOrder()));
            // mock 账单行无 updatedAt：以 dataAsOf（数据时点）降序近似「最近变更」，再按房号稳定排序
            case UPDATED_AT_DESC -> Comparator
                    .comparing(BillInfo::dataAsOf, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(BillInfo::roomCode, Comparator.nullsLast(Comparator.naturalOrder()));
        };
    }

    /** 楼栋筛选：账单行只有 roomCode（楼栋-单元-房号），按首段推导楼栋号比较 */
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

    private static String enumValuesText(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && !item.isNull()) {
                values.add(item.asText());
            }
        }
        return values.isEmpty() ? null : String.join("/", values);
    }

    private Map<String, String> idempotencyHeaders(String idempotencyKey) {
        return idempotencyKey == null || idempotencyKey.isBlank()
                ? Map.of()
                : Map.of("Idempotency-Key", idempotencyKey);
    }

    private Ticket mapTicket(Capability capability, JsonNode envelope) {
        JsonNode data = data(capability, envelope);
        String ticketNo = ErpFieldMapper.textOr(data, "ticketNo", "");
        return ErpFieldMapper.mapTicket(data, ErpFieldMapper.erpRef(properties.getVendorLabel(), ticketNo),
                isMock(envelope));
    }

    private List<com.property.ai.bff.dto.TicketListItem> mapListItems(JsonNode items, boolean mock) {
        if (items == null || !items.isArray()) {
            return List.of();
        }
        List<com.property.ai.bff.dto.TicketListItem> rows = new ArrayList<>();
        for (JsonNode item : items) {
            rows.add(ErpFieldMapper.mapTicketListItem(item, mock));
        }
        return rows;
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

    private void ensureSupported(Capability capability) {
        if (!supports(capability)) {
            throw ErpErrorMapper.unsupported(capability);
        }
    }

    private static String encodePath(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
