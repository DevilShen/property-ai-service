package com.property.ai.domain.copilot;

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
 * Copilot 只读演示数据源（P4-011，契约 v1.2.0 {@code /copilot/portrait|digest|report}）。
 *
 * <h2>数据来源（复用仓库既有 mock 数据，不新建、不编造）</h2>
 * <ul>
 *   <li>{@code mock/data/households.json}（20 户）：房号 → 掩码业主名 / 楼栋单元 / 运营标签 / 绑定状态 / 逾期天数
 *       （<b>不含手机号明文</b>）；</li>
 *   <li>{@code mock/data/tickets.json}（14 单历史样本）：画像服务历史计数与日报/报表的工单口径；</li>
 *   <li>{@code mock/data/bills.json}（账单样本）：画像的账单交互计数（<b>不含金额</b>）。</li>
 * </ul>
 *
 * <p><b>脱敏约束</b>：本类只解析画像/报表需要的计数字段，<b>不解析</b> {@code phoneTail4}、
 * {@code arrearsAmount}、账单 {@code total} 等隐私/金额字段；上层 DTO 也不下发这些字段。
 *
 * <p>路径解析：配置 {@code property.copilot.data-dir}（显式覆盖）→ 从工作目录向上 6 层查找
 * {@code mock/data/households.json}；缺失时返回空集合（页面空态，不编造数据）。
 */
@Component
public class CopilotDataRepository {

    private static final Logger log = LoggerFactory.getLogger(CopilotDataRepository.class);

    private static final String RELATIVE_PATH = "mock/data/households.json";
    private static final int MAX_WALK_UP = 6;

    private final ObjectMapper objectMapper;
    private final String configuredDir;

    public CopilotDataRepository(ObjectMapper objectMapper,
            @Value("${property.copilot.data-dir:}") String configuredDir) {
        this.objectMapper = objectMapper;
        this.configuredDir = configuredDir == null ? "" : configuredDir.trim();
    }

    /** 房档（脱敏口径：无手机号、无欠费金额）。 */
    public record Household(String roomCode, String building, String unit, String ownerNameMasked,
            List<String> tags, String bindState, int overdueDays) {
    }

    /** 历史工单样本（计数口径：房号/类别/状态/建单时间/AI 建单标记）。 */
    public record SeedTicket(String ticketNo, String roomCode, String category, String symptom, String status,
            String createdAt, String updatedAt, boolean aiCreated) {
    }

    /** 账单样本（计数口径：房号/账期/状态；<b>不解析金额</b>）。 */
    public record SeedBill(String billNo, String roomCode, String period, String status) {
    }

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
            rows.add(new Household(roomCode, text(node, "building"), text(node, "unit"),
                    text(node, "ownerNameMasked"), textArray(node, "tags"), text(node, "bindState"),
                    node.path("overdueDays").asInt(0)));
        }
        return List.copyOf(rows);
    }

    public Optional<Household> household(String roomCode) {
        if (roomCode == null || roomCode.isBlank()) {
            return Optional.empty();
        }
        String normalized = roomCode.trim();
        return households().stream().filter(row -> normalized.equals(row.roomCode())).findFirst();
    }

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
            rows.add(new SeedTicket(ticketNo, text(node, "roomNo"), text(node, "category"),
                    text(node, "symptom"), text(node, "status"), text(node, "createdAt"),
                    text(node, "updatedAt"), node.path("aiCreated").asBoolean(true)));
        }
        return List.copyOf(rows);
    }

    public List<SeedBill> bills() {
        JsonNode root = read("bills.json");
        if (root == null || !root.isArray()) {
            return List.of();
        }
        List<SeedBill> rows = new ArrayList<>();
        for (JsonNode node : root) {
            rows.add(new SeedBill(text(node, "billNo"), text(node, "roomNo"), text(node, "period"),
                    text(node, "status")));
        }
        return List.copyOf(rows);
    }

    /** 房号 → 历史工单数（近 12 个月样本口径）。 */
    public int ticketCount(String roomCode) {
        if (roomCode == null) {
            return 0;
        }
        int count = 0;
        for (SeedTicket ticket : tickets()) {
            if (roomCode.equals(ticket.roomCode())) {
                count++;
            }
        }
        return count;
    }

    /** 房号 → 账单交互数（账单样本条数；不含金额）。 */
    public int billCount(String roomCode) {
        if (roomCode == null) {
            return 0;
        }
        int count = 0;
        for (SeedBill bill : bills()) {
            if (roomCode.equals(bill.roomCode())) {
                count++;
            }
        }
        return count;
    }

    /** 房号 → 工单号集合（与平台侧记录去重时使用）。 */
    public Map<String, List<String>> ticketNosByRoom() {
        Map<String, List<String>> rows = new LinkedHashMap<>();
        for (SeedTicket ticket : tickets()) {
            rows.computeIfAbsent(ticket.roomCode(), key -> new ArrayList<>()).add(ticket.ticketNo());
        }
        return rows;
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    private JsonNode read(String fileName) {
        Path dir = resolveDir();
        if (dir == null) {
            log.warn("[copilot] 未找到 Copilot 演示数据目录（{}），画像/日报按空数据降级", RELATIVE_PATH);
            return null;
        }
        Path path = dir.resolve(fileName);
        if (!Files.isRegularFile(path)) {
            log.warn("[copilot] Copilot 演示数据文件缺失：{}", path);
            return null;
        }
        try {
            return objectMapper.readTree(Files.readString(path));
        } catch (IOException ex) {
            log.warn("[copilot] Copilot 演示数据读取失败 {}：{}", path, ex.getClass().getSimpleName());
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
            Path candidate = base.resolve(RELATIVE_PATH);
            if (Files.isRegularFile(candidate)) {
                Path parent = candidate.getParent();
                return parent == null ? null : parent.toAbsolutePath().normalize();
            }
            base = base.getParent();
        }
        return null;
    }

    /** 诊断/报告用：数据目录（未解析到为空串）。 */
    public String dataDir() {
        Path dir = resolveDir();
        return dir == null ? "" : dir.toString();
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
}
