package com.property.ai.storage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.property.ai.bff.dto.ErpRef;
import com.property.ai.bff.dto.IssueScope;
import com.property.ai.bff.dto.TicketAuditNode;
import com.property.ai.bff.dto.TicketCategory;
import com.property.ai.bff.dto.TicketStatus;
import com.property.ai.bff.dto.TicketUrgency;
import com.property.ai.domain.ticket.ErpWritebackState;
import com.property.ai.domain.ticket.TicketRecord;
import com.property.ai.domain.ticket.TicketRepository;

/**
 * 工单仓储 PostgreSQL 实现（P5-017，开关 {@code property.storage.mode=postgres}）。
 *
 * <h2>依赖边界（AGENTS.md：不新增 Maven 依赖）</h2>
 * 只用 JDK 自带 {@code java.sql} / {@code DriverManager} + {@code Class.forName} 反射加载驱动，
 * <b>pom 不引入</b> {@code org.postgresql:postgresql}；生产接入时加驱动依赖即可直接复用本类
 * （坐标与步骤见 {@code docs/ops/PG-迁移方案.md} §2）。
 *
 * <h2>启动期探测 + 降级（不得假装生产就绪）</h2>
 * {@link #initialize()} 顺序探测：
 * <ol>
 *   <li>{@code mode=memory} → 直接内存（不尝试连库）；</li>
 *   <li>驱动类不在 classpath（本环境恒成立）→ WARN 降级 memory；</li>
 *   <li>{@code jdbc-url} 未配置 → WARN 降级 memory；</li>
 *   <li>建连失败（{@code isValid} false）→ WARN 降级 memory。</li>
 * </ol>
 * 探测失败只降级不阻断启动（本环境无 PG 实例）；生效模式/原因通过 {@link #storageStatus()} 暴露
 * （既有 {@link TicketRepository} 接口形状不变，见 {@link StorageModeAware}）。
 *
 * <h2>运行期失败</h2>
 * 探测通过后 SQL 失败抛 {@link StorageAccessException}（**不**运行期自动回落内存，避免双写分叉），
 * 回滚见 {@code docs/ops/PG-迁移方案.md} §7。
 *
 * <h2>表结构</h2>
 * 见 {@code docs/ops/PG-schema.sql} 的 {@code property_ai.tickets}：标量列 + {@code payload jsonb}
 * （平台侧扩展：节点快照/回访结论/催办次数/erp 引用等），{@code project_id} 为分区键（HASH 4 分区示例）。
 */
public class JdbcTicketRepository implements TicketRepository, StorageModeAware {

    private static final Logger log = LoggerFactory.getLogger(JdbcTicketRepository.class);

    /** 业务时区（与 {@code ConnectorTimes} 一致：Asia/Shanghai） */
    private static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");

    /** 显式列清单（禁止 {@code SELECT *}，保证 schema 演进时可读） */
    private static final String COLUMNS = "ticket_no, project_id, household_id, room_code, owner_id, category, urgency, "
            + "location, symptom, status, team, eta, version, erp_writeback, payload, created_at, updated_at";

    private static final String UPSERT_SQL = """
            INSERT INTO tickets (ticket_no, project_id, household_id, room_code, owner_id, category, urgency,
                                 location, symptom, status, team, eta, version, erp_writeback, payload,
                                 created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb),
                    CAST(? AS timestamptz), CAST(? AS timestamptz))
            ON CONFLICT (ticket_no) DO UPDATE SET
                project_id = EXCLUDED.project_id,
                household_id = EXCLUDED.household_id,
                room_code = EXCLUDED.room_code,
                owner_id = EXCLUDED.owner_id,
                category = EXCLUDED.category,
                urgency = EXCLUDED.urgency,
                location = EXCLUDED.location,
                symptom = EXCLUDED.symptom,
                status = EXCLUDED.status,
                team = EXCLUDED.team,
                eta = EXCLUDED.eta,
                version = EXCLUDED.version,
                erp_writeback = EXCLUDED.erp_writeback,
                payload = EXCLUDED.payload,
                updated_at = EXCLUDED.updated_at
            WHERE tickets.version <= EXCLUDED.version
            """;

    private final StorageProperties properties;
    private final TicketRepository memoryFallback;
    private final ObjectMapper objectMapper;

    private boolean initialized;
    private volatile boolean available;
    private volatile String unavailableReason;

    public JdbcTicketRepository(StorageProperties properties, TicketRepository memoryFallback, ObjectMapper objectMapper) {
        this.properties = properties == null ? new StorageProperties() : properties;
        this.memoryFallback = memoryFallback;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    // ------------------------------------------------------------------
    // 启动期探测
    // ------------------------------------------------------------------

    /**
     * 启动期探测（幂等，可重复调用）。返回值 = 是否真正可用 PostgreSQL。
     *
     * <p>失败只 WARN 并降级内存，不抛异常（本环境无 PG 实例/驱动，服务必须能起）。
     */
    public synchronized boolean initialize() {
        if (initialized) {
            return available;
        }
        initialized = true;
        if (!properties.isModeValid()) {
            log.warn("[storage] WARN 无法识别的 property.storage.mode={}，按 memory 处理（合法值：memory|postgres）",
                    properties.getMode());
        }
        if (!properties.isPostgresRequested()) {
            unavailableReason = "property.storage.mode=" + properties.requestedMode().value() + "（未启用 PostgreSQL）";
            available = false;
            log.info("[storage] storageMode=memory（demo 内存实现：重启清空、非生产存储）");
            return false;
        }
        String reason = probe();
        if (reason == null) {
            available = true;
            unavailableReason = null;
            log.info("[storage] storageMode=postgres url={} schema=property_ai（PG 16；表结构见 docs/ops/PG-schema.sql）",
                    maskJdbcUrl(properties.getJdbcUrl()));
            return true;
        }
        available = false;
        unavailableReason = reason;
        log.warn("[storage] WARN 请求 storage=postgres 但不可用，已降级为内存实现：{}（接入步骤见 docs/ops/PG-迁移方案.md §2/§4；"
                + "降级期间不落 PG、重启丢平台侧数据，不得表述为生产就绪）", reason);
        return false;
    }

    /** 探测：返回 null 表示可用，否则返回不可用原因（不含口令） */
    private String probe() {
        String driverClass = properties.getDriverClass();
        if (driverClass == null || driverClass.isBlank()) {
            return "property.storage.driver-class 未配置";
        }
        try {
            Class.forName(driverClass);
        } catch (ClassNotFoundException | LinkageError e) {
            return "classpath 缺少 PostgreSQL JDBC 驱动 " + driverClass
                    + "（本环境按 AGENTS.md 不新增 Maven 依赖；生产坐标 org.postgresql:postgresql:42.7.4"
                    + "，见 docs/ops/PG-迁移方案.md §2）";
        }
        if (properties.getJdbcUrl() == null || properties.getJdbcUrl().isBlank()) {
            return "property.storage.jdbc-url 未配置（示例 jdbc:postgresql://127.0.0.1:5432/property_ai?currentSchema=property_ai）";
        }
        try (Connection connection = openConnection()) {
            if (!connection.isValid(Math.max(1, properties.getConnectTimeoutSeconds()))) {
                return "连接校验失败 isValid=false（url=" + maskJdbcUrl(properties.getJdbcUrl()) + "）";
            }
        } catch (SQLException e) {
            return "连接失败（url=" + maskJdbcUrl(properties.getJdbcUrl()) + "）：" + e.getMessage();
        }
        return null;
    }

    private Connection openConnection() throws SQLException {
        String url = properties.getJdbcUrl();
        Properties info = new Properties();
        if (properties.getUsername() != null && !properties.getUsername().isBlank()) {
            info.setProperty("user", properties.getUsername());
        }
        if (properties.getPassword() != null && !properties.getPassword().isBlank()) {
            info.setProperty("password", properties.getPassword());
        }
        info.setProperty("connectTimeout", String.valueOf(Math.max(1, properties.getConnectTimeoutSeconds())));
        info.setProperty("loginTimeout", String.valueOf(Math.max(1, properties.getConnectTimeoutSeconds())));
        return DriverManager.getConnection(url, info);
    }

    /** 掩码 JDBC URL（去掉 {@code user:password@} 段，日志/异常不泄露口令） */
    static String maskJdbcUrl(String url) {
        if (url == null) {
            return "";
        }
        return url.replaceAll("//[^/@]*@", "//***@");
    }

    // ------------------------------------------------------------------
    // StorageModeAware
    // ------------------------------------------------------------------

    @Override
    public StorageStatus storageStatus() {
        StorageMode requested = properties.requestedMode();
        StorageMode effective = available ? StorageMode.POSTGRES : StorageMode.MEMORY;
        return new StorageStatus(requested, effective, available ? null : unavailableReason);
    }

    /** 生效存储模式：{@code memory} | {@code postgres} */
    public String storageMode() {
        return storageStatus().effectiveMode();
    }

    // ------------------------------------------------------------------
    // TicketRepository
    // ------------------------------------------------------------------

    @Override
    public Optional<TicketRecord> find(String ticketNo) {
        if (!available) {
            return memoryFallback.find(ticketNo);
        }
        if (ticketNo == null || ticketNo.isBlank()) {
            return Optional.empty();
        }
        String sql = "SELECT " + COLUMNS + " FROM tickets WHERE ticket_no = ?";
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ticketNo);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readRecord(resultSet)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new StorageAccessException("PG 查询工单失败 ticketNo=" + ticketNo, e);
        }
    }

    @Override
    public void save(TicketRecord record) {
        if (record == null || record.ticketNo == null || record.ticketNo.isBlank()) {
            throw new IllegalArgumentException("工单记录缺少 ticketNo，无法保存");
        }
        if (!available) {
            memoryFallback.save(record);
            return;
        }
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(UPSERT_SQL)) {
            bindUpsert(statement, record);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new StorageAccessException("PG 保存工单失败 ticketNo=" + record.ticketNo, e);
        }
    }

    @Override
    public List<TicketRecord> findAll() {
        if (!available) {
            return memoryFallback.findAll();
        }
        String sql = "SELECT " + COLUMNS + " FROM tickets ORDER BY updated_at DESC";
        List<TicketRecord> records = new ArrayList<>();
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                records.add(readRecord(resultSet));
            }
            return List.copyOf(records);
        } catch (SQLException e) {
            throw new StorageAccessException("PG 查询工单列表失败", e);
        }
    }

    @Override
    public long count() {
        if (!available) {
            return memoryFallback.count();
        }
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT count(*) FROM tickets");
                ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new StorageAccessException("PG 统计工单数失败", e);
        }
    }

    /**
     * 清空工单表（仅联调；生产实现不提供）。
     *
     * <p>PG 模式下默认抛 {@link UnsupportedOperationException}，需显式
     * {@code property.storage.allow-reset=true}（演示/回灌演练）才会执行 {@code DELETE}。
     */
    @Override
    public void clear() {
        if (!available) {
            memoryFallback.clear();
            return;
        }
        if (!properties.isAllowReset()) {
            throw new UnsupportedOperationException(
                    "PostgreSQL 实现不提供运行时清空（如需演示重置请显式设置 property.storage.allow-reset=true）");
        }
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement("DELETE FROM tickets")) {
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new StorageAccessException("PG 清空工单表失败", e);
        }
    }

    // ------------------------------------------------------------------
    // 行映射
    // ------------------------------------------------------------------

    private void bindUpsert(PreparedStatement statement, TicketRecord record) throws SQLException {
        statement.setString(1, record.ticketNo);
        statement.setString(2, defaultProject(record.projectId));
        statement.setString(3, orEmpty(record.householdId));
        statement.setString(4, orEmpty(record.roomCode));
        statement.setString(5, orEmpty(record.ownerId));
        statement.setString(6, record.category == null ? null : record.category.value());
        statement.setString(7, record.urgency == null ? null : record.urgency.value());
        statement.setString(8, orEmpty(record.location));
        statement.setString(9, record.symptom);
        statement.setString(10, record.status == null ? null : record.status.value());
        statement.setString(11, orEmpty(record.team));
        statement.setString(12, orEmpty(record.eta));
        statement.setInt(13, Math.max(1, record.version));
        statement.setString(14, writebackJson(record.writeback));
        statement.setString(15, payloadJson(record));
        statement.setObject(16, parseTimestampOrNow(record.createdAt));
        statement.setObject(17, parseTimestampOrNow(record.updatedAt));
    }

    private TicketRecord readRecord(ResultSet resultSet) throws SQLException {
        TicketRecord record = new TicketRecord();
        record.ticketNo = resultSet.getString("ticket_no");
        record.projectId = orEmpty(resultSet.getString("project_id"));
        record.householdId = orEmpty(resultSet.getString("household_id"));
        record.roomCode = orEmpty(resultSet.getString("room_code"));
        record.ownerId = orEmpty(resultSet.getString("owner_id"));
        record.category = enumOrNull(TicketCategory.class, resultSet.getString("category"));
        record.urgency = enumOrNull(TicketUrgency.class, resultSet.getString("urgency"));
        record.location = orEmpty(resultSet.getString("location"));
        record.symptom = resultSet.getString("symptom");
        record.status = enumOrDefault(TicketStatus.class, resultSet.getString("status"), TicketStatus.CREATED);
        record.team = orEmpty(resultSet.getString("team"));
        record.eta = orEmpty(resultSet.getString("eta"));
        record.version = Math.max(1, resultSet.getInt("version"));
        record.writeback = readWriteback(resultSet.getString("erp_writeback"));
        record.createdAt = isoOrEmpty(resultSet.getObject("created_at", OffsetDateTime.class));
        record.updatedAt = isoOrEmpty(resultSet.getObject("updated_at", OffsetDateTime.class));
        applyPayload(record, resultSet.getString("payload"));
        return record;
    }

    private String payloadJson(TicketRecord record) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("downstairs", record.downstairs == null ? null : record.downstairs.value());
        payload.put("duration", record.duration);
        payload.put("slot", record.slot);
        payload.put("note", record.note);
        payload.put("aiCreated", record.aiCreated);
        payload.put("mock", record.mock);
        payload.put("erp", record.erp);
        payload.put("traceId", record.traceId);
        payload.put("erpNodes", record.erpNodes);
        payload.put("localNodes", record.localNodes);
        payload.put("feedbackScore", record.feedbackScore);
        payload.put("feedbackTags", record.feedbackTags);
        payload.put("feedbackNote", record.feedbackNote);
        payload.put("reviewedAt", record.reviewedAt);
        payload.put("urgeCount", record.urgeCount);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new StorageAccessException("工单 payload 序列化失败 ticketNo=" + record.ticketNo, e);
        }
    }

    /**
     * ERP 回写状态 → {@code erp_writeback jsonb}（平台侧内部状态，不落 ERP 主数据）。
     *
     * <p>{@link ErpWritebackState} 是可变 POJO（私有构造 + 工厂），这里显式列字段序列化，
     * 反序列化用工厂重建后回填可变字段，避免依赖 Jackson 的私有构造器反序列化。
     */
    private String writebackJson(ErpWritebackState writeback) {
        if (writeback == null) {
            return null;
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("kind", writeback.kind == null ? null : writeback.kind.name());
        json.put("status", writeback.status == null ? null : writeback.status.value());
        json.put("attempts", writeback.attempts);
        json.put("issuedAt", writeback.issuedAt == null ? null : writeback.issuedAt.toString());
        json.put("confirmDueAt", writeback.confirmDueAt == null ? null : writeback.confirmDueAt.toString());
        json.put("erpRef", writeback.erpRef);
        json.put("message", writeback.message);
        json.put("targetStatus", writeback.targetStatus == null ? null : writeback.targetStatus.value());
        json.put("confirmAttempts", writeback.confirmAttempts);
        json.put("confirmedAt", writeback.confirmedAt == null ? null : writeback.confirmedAt.toString());
        try {
            return objectMapper.writeValueAsString(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new StorageAccessException("ERP 回写状态序列化失败", e);
        }
    }

    private ErpWritebackState readWriteback(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
            ErpWritebackState.Kind kind = enumOrNull(ErpWritebackState.Kind.class, stringOrNull(raw.get("kind")));
            if (kind == null) {
                return null;
            }
            com.property.ai.bff.dto.ErpWritebackStatus status = enumOrNull(
                    com.property.ai.bff.dto.ErpWritebackStatus.class, stringOrNull(raw.get("status")));
            Instant issuedAt = instantOrNow(stringOrNull(raw.get("issuedAt")));
            TicketStatus targetStatus = enumOrNull(TicketStatus.class, stringOrNull(raw.get("targetStatus")));
            ErpWritebackState state = switch (status == null
                    ? com.property.ai.bff.dto.ErpWritebackStatus.FAILED : status) {
                case PENDING -> ErpWritebackState.pending(kind, stringOrNull(raw.get("erpRef")), targetStatus, issuedAt);
                case CONFIRMED -> {
                    ErpWritebackState confirmed = ErpWritebackState.pending(kind, stringOrNull(raw.get("erpRef")),
                            targetStatus, issuedAt);
                    confirmed.status = com.property.ai.bff.dto.ErpWritebackStatus.CONFIRMED;
                    confirmed.confirmedAt = instantOrNull(stringOrNull(raw.get("confirmedAt")));
                    yield confirmed;
                }
                case FAILED -> ErpWritebackState.failed(kind, stringOrNull(raw.get("erpRef")),
                        stringOrNull(raw.get("message")), issuedAt);
            };
            state.attempts = intOrDefault(raw.get("attempts"), state.attempts);
            state.confirmAttempts = intOrDefault(raw.get("confirmAttempts"), 0);
            OffsetDateTime confirmDueAt = parseTimestampOrNull(stringOrNull(raw.get("confirmDueAt")));
            if (confirmDueAt != null) {
                state.confirmDueAt = confirmDueAt.toInstant();
            }
            state.message = stringOrNull(raw.get("message"));
            state.erpRef = stringOrNull(raw.get("erpRef"));
            state.targetStatus = targetStatus;
            return state;
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException e) {
            throw new StorageAccessException("ERP 回写状态反序列化失败", e);
        }
    }

    private void applyPayload(TicketRecord record, String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
            record.downstairs = enumOrDefault(IssueScope.class, stringOrNull(payload.get("downstairs")),
                    IssueScope.UNKNOWN);
            record.duration = stringOrEmpty(payload.get("duration"));
            record.slot = stringOrEmpty(payload.get("slot"));
            record.note = stringOrNull(payload.get("note"));
            record.aiCreated = booleanOrDefault(payload.get("aiCreated"), true);
            record.mock = booleanOrDefault(payload.get("mock"), true);
            record.erp = payload.get("erp") == null ? null
                    : objectMapper.convertValue(payload.get("erp"), ErpRef.class);
            record.traceId = stringOrNull(payload.get("traceId"));
            record.erpNodes = nodes(payload.get("erpNodes"));
            record.localNodes = nodes(payload.get("localNodes"));
            record.feedbackScore = payload.get("feedbackScore") == null ? null
                    : objectMapper.convertValue(payload.get("feedbackScore"), Integer.class);
            record.feedbackTags = stringList(payload.get("feedbackTags"));
            record.feedbackNote = stringOrNull(payload.get("feedbackNote"));
            record.reviewedAt = stringOrNull(payload.get("reviewedAt"));
            record.urgeCount = payload.get("urgeCount") == null ? 0
                    : objectMapper.convertValue(payload.get("urgeCount"), Integer.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException e) {
            throw new StorageAccessException("工单 payload 反序列化失败 ticketNo=" + record.ticketNo, e);
        }
    }

    private List<TicketAuditNode> nodes(Object raw) {
        if (raw == null) {
            return new ArrayList<>();
        }
        try {
            List<TicketAuditNode> nodes = objectMapper.convertValue(raw, new TypeReference<List<TicketAuditNode>>() {
            });
            return nodes == null ? new ArrayList<>() : new ArrayList<>(nodes);
        } catch (IllegalArgumentException e) {
            throw new StorageAccessException("工单节点 payload 反序列化失败", e);
        }
    }

    private List<String> stringList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        try {
            List<String> values = objectMapper.convertValue(raw, new TypeReference<List<String>>() {
            });
            return values == null ? List.of() : List.copyOf(values);
        } catch (IllegalArgumentException e) {
            throw new StorageAccessException("工单标签 payload 反序列化失败", e);
        }
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static <E extends Enum<E>> E enumOrDefault(Class<E> type, String raw, E fallback) {
        E parsed = enumOrNull(type, raw);
        return parsed == null ? fallback : parsed;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String orEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String stringOrEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean booleanOrDefault(Object value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private static int intOrDefault(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Instant instantOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private static Instant instantOrNow(String raw) {
        Instant parsed = instantOrNull(raw);
        return parsed == null ? Instant.now() : parsed;
    }

    private static OffsetDateTime parseTimestampOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw.trim());
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private static String defaultProject(String projectId) {
        return projectId == null || projectId.isBlank()
                ? com.property.ai.domain.project.ProjectService.DEFAULT_PROJECT_ID
                : projectId;
    }

    private static OffsetDateTime parseTimestampOrNow(String raw) {
        if (raw == null || raw.isBlank()) {
            return OffsetDateTime.now(BIZ_ZONE);
        }
        try {
            return OffsetDateTime.parse(raw.trim());
        } catch (java.time.format.DateTimeParseException e) {
            return OffsetDateTime.now(BIZ_ZONE);
        }
    }

    private static String isoOrEmpty(OffsetDateTime value) {
        return value == null ? "" : value.atZoneSameInstant(BIZ_ZONE).toOffsetDateTime().toString();
    }
}
