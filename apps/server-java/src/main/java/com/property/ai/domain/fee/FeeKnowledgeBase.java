package com.property.ai.domain.fee;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 费用解释的知识出处（P3 W10，账单解释必须可追溯）。
 *
 * <p><b>数据源</b>：复用仓库既有知识库 mock 数据 {@code mock/data/knowledge.json}
 * （20 条：K-001 物业费单价、K-002 公摊电费、K-003 车位费、K-014 滞纳金…），
 * <b>不新建知识数据、不在 Java 里硬编码知识正文</b>。路径解析顺序：
 * <ol>
 *   <li>配置 {@code property.fee.knowledge-path}（显式覆盖，测试/部署用）；</li>
 *   <li>从进程工作目录向上最多 6 层查找 {@code mock/data/knowledge.json}
 *       （兼容 {@code pnpm test:server} 与 {@code mvnw -f apps/server-java} 两种启动目录）。</li>
 * </ol>
 *
 * <p><b>拒答口径</b>：只有 {@code status=published} 的条目可作为解释出处；
 * 条目缺失 / 已过期（如 K-009）/ 无出处时由 {@link FeeExplainService} 直接拒答
 * （404 {@code E-6002} 引用缺失），<b>不得编造</b>。
 *
 * <p>本类只读文件、只做 ID → 条目查询；不参与金额计算。
 */
@Component
public class FeeKnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(FeeKnowledgeBase.class);

    private static final String RELATIVE_PATH = "mock/data/knowledge.json";
    private static final int MAX_WALK_UP = 6;

    private final ObjectMapper objectMapper;
    private final String configuredPath;
    private final AtomicReference<List<Entry>> entries = new AtomicReference<>();

    public FeeKnowledgeBase(ObjectMapper objectMapper,
            @Value("${property.fee.knowledge-path:}") String configuredPath) {
        this.objectMapper = objectMapper;
        this.configuredPath = configuredPath == null ? "" : configuredPath.trim();
    }

    /**
     * 知识条目（只取解释需要的可追溯字段：ID/标题/版本/状态；正文不返回给前端）。
     */
    public record Entry(
            String knowledgeId,
            String title,
            String version,
            String status,
            String effectiveDate,
            String category) {

        /** 只有 published 条目可作为解释出处（expired/draft 一律拒答）。 */
        public boolean published() {
            return status != null && "published".equalsIgnoreCase(status.trim());
        }
    }

    /** 全部已加载条目（只读；缺失文件时为空列表）。 */
    public List<Entry> all() {
        return load();
    }

    /** 按 ID 查条目（不过滤状态，供诊断/测试）。 */
    public Optional<Entry> find(String knowledgeId) {
        if (knowledgeId == null || knowledgeId.isBlank()) {
            return Optional.empty();
        }
        String normalized = knowledgeId.trim();
        return load().stream().filter(item -> normalized.equalsIgnoreCase(item.knowledgeId())).findFirst();
    }

    /** 按 ID 查<b>可引用</b>条目：必须存在且 {@code published}。 */
    public Optional<Entry> findPublished(String knowledgeId) {
        return find(knowledgeId).filter(Entry::published);
    }

    /** 当前解析到的知识文件路径（诊断/报告用；未找到为 {@code null}）。 */
    public Path sourcePath() {
        return resolvePath();
    }

    /** 强制重载（测试/知识运营变更后使用）。 */
    public void reload() {
        entries.set(null);
    }

    // ------------------------------------------------------------------
    // 加载
    // ------------------------------------------------------------------

    private List<Entry> load() {
        List<Entry> current = entries.get();
        if (current != null) {
            return current;
        }
        synchronized (this) {
            current = entries.get();
            if (current != null) {
                return current;
            }
            List<Entry> loaded = readEntries();
            entries.set(loaded);
            return loaded;
        }
    }

    private List<Entry> readEntries() {
        Path path = resolvePath();
        if (path == null) {
            log.warn("[fee] 未找到知识库 mock 数据（{}），账单解释将按「无出处」拒答（E-6002）", RELATIVE_PATH);
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readString(path));
            if (!root.isArray()) {
                log.warn("[fee] 知识库 mock 数据格式非法（不是数组）：{}", path);
                return List.of();
            }
            List<Entry> rows = new ArrayList<>();
            for (JsonNode node : root) {
                String knowledgeId = text(node, "knowledgeId");
                if (knowledgeId == null) {
                    continue;
                }
                rows.add(new Entry(
                        knowledgeId,
                        text(node, "title"),
                        text(node, "version"),
                        text(node, "status"),
                        text(node, "effectiveDate"),
                        text(node, "category")));
            }
            log.info("[fee] 知识库 mock 数据已加载 {} 条（{}）", rows.size(), path);
            return List.copyOf(rows);
        } catch (IOException ex) {
            log.warn("[fee] 知识库 mock 数据读取失败 {}：{}", path, ex.getClass().getSimpleName());
            return List.of();
        }
    }

    private Path resolvePath() {
        if (!configuredPath.isBlank()) {
            Path configured = Paths.get(configuredPath);
            return Files.isRegularFile(configured) ? configured.toAbsolutePath().normalize() : null;
        }
        Path base = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (int depth = 0; depth <= MAX_WALK_UP && base != null; depth++) {
            Path candidate = base.resolve(RELATIVE_PATH);
            if (Files.isRegularFile(candidate)) {
                return candidate.normalize();
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

    /** 状态枚举口径（诊断/测试用；未知状态按不可引用处理）。 */
    public static boolean publishable(String status) {
        return status != null && "published".equals(status.toLowerCase(Locale.ROOT));
    }
}
