package com.property.ai.domain.desk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 坐席队列的<b>只读演示数据源</b>（P4-012，契约 v1.2.0 {@code /desk/queue}）。
 *
 * <h2>数据来源（复用仓库既有 mock 数据，不新建、不编造）</h2>
 * <ul>
 *   <li>{@code mock/data/households.json}（20 户）：{@code roomNo} → {@code ownerNameMasked}/楼栋单元/标签；
 *       队列项只回掩码业主名，<b>不回手机号/房号明文以外的隐私</b>；</li>
 *   <li>{@code mock/data/tickets.json}（14 单历史样本）：未结单（非 completed/reviewed/archived）
 *       作为「待人工接入的会话」来源；会话 ID 由工单号确定性派生（{@code WO-20261114-0031} →
 *       {@code CONV-20261114-0031}），进程重启后 ID 稳定。</li>
 * </ul>
 *
 * <p><b>演示口径（P4，不得说成真实会话存储）</b>：队列的「等待起点」在进程内装载时按固定演示阶梯
 * 回填（见 {@code DeskConversationRepository}），不是从真实 IM 会话表读取；P5 接会话存储/事件流后
 * 改由真实 {@code enqueuedAt} 驱动，契约形状不变。
 *
 * <p>路径解析：配置 {@code property.desk.data-dir}（显式覆盖）→ 从工作目录向上 6 层查找
 * {@code mock/data/households.json}。文件缺失/格式非法时返回空集合（不编造，页面走空态）。
 */
@Component
public class DeskSeedData {

    private static final Logger log = LoggerFactory.getLogger(DeskSeedData.class);

    private static final String RELATIVE_PATH = "mock/data/households.json";
    private static final int MAX_WALK_UP = 6;

    private final ObjectMapper objectMapper;
    private final String configuredDir;

    public DeskSeedData(ObjectMapper objectMapper, @Value("${property.desk.data-dir:}") String configuredDir) {
        this.objectMapper = objectMapper;
        this.configuredDir = configuredDir == null ? "" : configuredDir.trim();
    }

    /** 房档（只取会话展示需要的脱敏字段；金额/手机号不进入队列数据）。 */
    public record Household(String roomCode, String ownerNameMasked, String building, String unit,
            List<String> tags, String bindState, int overdueDays) {
    }

    /** 历史工单样本（会话来源）。 */
    public record SeedTicket(String ticketNo, String roomCode, String category, String symptom,
            String status, String createdAt, boolean aiCreated) {
    }

    /** 全部房档（文件缺失返回空列表）。 */
    public List<Household> households() {
        JsonNode root = read("households.json");
        if (root == null || !root.isArray()) {
            return List.of();
        }
        List<Household> rows = new ArrayList<>();
        for (JsonNode node : root) {
            String roomCode = text(node, "roomNo");
            if (roomCode == null) {
                continue;
            }
            rows.add(new Household(
                    roomCode,
                    text(node, "ownerNameMasked"),
                    text(node, "building"),
                    text(node, "unit"),
                    textArray(node, "tags"),
                    text(node, "bindState"),
                    node.path("overdueDays").asInt(0)));
        }
        return List.copyOf(rows);
    }

    /** 房号 → 房档（大小写/空白归一）。 */
    public Optional<Household> household(String roomCode) {
        if (roomCode == null || roomCode.isBlank()) {
            return Optional.empty();
        }
        String normalized = roomCode.trim();
        return households().stream().filter(row -> normalized.equals(row.roomCode())).findFirst();
    }

    /** 历史工单样本（按文件顺序；文件缺失返回空列表）。 */
    public List<SeedTicket> tickets() {
        JsonNode root = read("tickets.json");
        if (root == null || !root.isArray()) {
            return List.of();
        }
        List<SeedTicket> rows = new ArrayList<>();
        for (JsonNode node : root) {
            String ticketNo = text(node, "ticketNo");
            if (ticketNo == null) {
                continue;
            }
            rows.add(new SeedTicket(
                    ticketNo,
                    text(node, "roomNo"),
                    text(node, "category"),
                    text(node, "symptom"),
                    text(node, "status"),
                    text(node, "createdAt"),
                    node.path("aiCreated").asBoolean(true)));
        }
        return List.copyOf(rows);
    }

    /** 会话预览文本（症状脱敏 + 截断；不含手机号/地址明文）。 */
    public static String preview(String symptom) {
        if (symptom == null || symptom.isBlank()) {
            return "业主发起会话，等待坐席接入";
        }
        String text = symptom.trim().replaceAll("\\s+", " ");
        return text.length() <= 40 ? text : text.substring(0, 40) + "…";
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    private JsonNode read(String fileName) {
        Path dir = resolveDir();
        if (dir == null) {
            log.warn("[desk] 未找到坐席演示数据目录（{}），队列数据为空（不编造）", RELATIVE_PATH);
            return null;
        }
        Path path = dir.resolve(fileName);
        if (!Files.isRegularFile(path)) {
            log.warn("[desk] 坐席演示数据文件缺失：{}（队列/质检按空数据降级）", path);
            return null;
        }
        try {
            return objectMapper.readTree(Files.readString(path));
        } catch (IOException ex) {
            log.warn("[desk] 坐席演示数据读取失败 {}：{}", path, ex.getClass().getSimpleName());
            return null;
        }
    }

    private Path resolveDir() {
        if (!configuredDir.isBlank()) {
            Path configured = Paths.get(configuredDir);
            return Files.isDirectory(configured) ? configured.toAbsolutePath().normalize() : null;
        }
        Path base = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (int depth = 0; depth <= MAX_WALK_UP && base != null; depth++) {
            Path candidate = base.resolve(RELATIVE_PATH).getParent();
            if (candidate != null && Files.isRegularFile(base.resolve(RELATIVE_PATH))) {
                return candidate.toAbsolutePath().normalize();
            }
            base = base.getParent();
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static List<String> textArray(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isArray() || value.isEmpty()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (JsonNode item : value) {
            String text = item.asText();
            if (text != null && !text.isBlank()) {
                items.add(text.trim());
            }
        }
        return List.copyOf(items);
    }

    /** 诊断/报告用：数据目录（未解析到为空串）。 */
    public String dataDir() {
        Path dir = resolveDir();
        return dir == null ? "" : dir.toString();
    }

    /** 房档索引（roomCode → household），便于上层批量装配。 */
    public Map<String, Household> householdIndex() {
        Map<String, Household> index = new LinkedHashMap<>();
        for (Household row : households()) {
            index.put(row.roomCode(), row);
        }
        return index;
    }
}
