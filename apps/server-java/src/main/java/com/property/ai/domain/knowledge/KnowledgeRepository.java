package com.property.ai.domain.knowledge;

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
import com.property.ai.bff.dto.KnowledgeCategory;
import com.property.ai.bff.dto.KnowledgeItemStatus;
import com.property.ai.bff.dto.KnowledgeSource;

/**
 * 知识库只读仓库（P4-005，契约 v1.2.0 §1.5「知识」）。
 *
 * <h2>数据源（唯一事实源 = 仓库既有 mock 数据，不新建、不硬编码正文）</h2>
 * {@code mock/data/knowledge.json}（44 条：published 36 / expired 4 / draft 2 / pending_review 1 /
 * rejected 1）。路径解析顺序：
 * <ol>
 *   <li>配置 {@code property.knowledge.path}（显式覆盖，测试/部署用）；</li>
 *   <li>从进程工作目录向上最多 6 层查找 {@code mock/data/knowledge.json}
 *       （兼容 {@code pnpm test:server} 与 {@code mvnw -f apps/server-java} 两种启动目录）。</li>
 * </ol>
 *
 * <h2>字段归一（兼容 P1 历史别名，见 {@code mock/data/README.md} §3.4）</h2>
 * <ul>
 *   <li>生效日：{@code effectiveFrom}，缺失时回退 P1 别名 {@code effectiveDate}；</li>
 *   <li>失效日：{@code effectiveTo}，缺失时回退兼容别名 {@code expireDate}；</li>
 *   <li>{@code badcase} 在 mock 里是<b>归因样本数组</b>（K-024/K-025/K-029），契约字段是
 *       <b>布尔标记</b>「是否来自 badcase 回流」——本仓库归一为布尔 + 样本条数，正文不外泄；</li>
 *   <li>{@code source} 归一为契约 {@link KnowledgeSource}（doc 必填；clause/page 可空）。</li>
 * </ul>
 *
 * <p><b>只读约束</b>：本类只读文件，<b>不写回</b> {@code mock/data/**}（AGENTS.md §5）。
 * 变更审核通过的版本覆盖在 {@link KnowledgeChangeService} 的内存表中，读取时优先覆盖版本；
 * 进程重启后覆盖丢失（demo 口径，P5 迁 PostgreSQL 时落库）。
 */
@Component
public class KnowledgeRepository {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRepository.class);

    private static final String RELATIVE_PATH = "mock/data/knowledge.json";
    private static final int MAX_WALK_UP = 6;

    private final ObjectMapper objectMapper;
    private final String configuredPath;
    private final AtomicReference<List<Entry>> entries = new AtomicReference<>();

    public KnowledgeRepository(ObjectMapper objectMapper,
            @Value("${property.knowledge.path:}") String configuredPath) {
        this.objectMapper = objectMapper;
        this.configuredPath = configuredPath == null ? "" : configuredPath.trim();
    }

    /**
     * 知识条目（仓库原始口径：只含读/检索/统计需要的字段；不返回 {@code forbidden}/{@code sourceText}
     * 等运营内部字段，也不返回真实人名/手机号）。
     */
    public record Entry(
            String knowledgeId,
            String title,
            String content,
            String version,
            KnowledgeItemStatus status,
            String owner,
            String effectiveFrom,
            String effectiveTo,
            KnowledgeSource source,
            KnowledgeCategory category,
            boolean badcase,
            int badcaseCount,
            List<String> refs,
            List<String> keywords,
            String updatedAt) {

        /** 是否「已发布」（检索只允许 published）。 */
        public boolean published() {
            return status == KnowledgeItemStatus.PUBLISHED;
        }
    }

    /** 全部条目（只读；文件缺失/格式非法时为空列表，不抛异常）。 */
    public List<Entry> all() {
        return load();
    }

    /** 按知识 ID 查（大小写不敏感；不过滤状态，供诊断/管理视图）。 */
    public Optional<Entry> find(String knowledgeId) {
        if (knowledgeId == null || knowledgeId.isBlank()) {
            return Optional.empty();
        }
        String normalized = knowledgeId.trim();
        return load().stream()
                .filter(item -> normalized.equalsIgnoreCase(item.knowledgeId()))
                .findFirst();
    }

    /** 当前解析到的知识文件路径（诊断/报告用；未找到为 {@code null}）。 */
    public Path sourcePath() {
        return resolvePath();
    }

    /** 强制重载（测试/知识运营变更后使用；不写回文件）。 */
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
            log.warn("[knowledge] 未找到知识库 mock 数据（{}），知识读接口返回空集合（不编造）", RELATIVE_PATH);
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readString(path));
            if (!root.isArray()) {
                log.warn("[knowledge] 知识库 mock 数据格式非法（不是数组）：{}", path);
                return List.of();
            }
            List<Entry> rows = new ArrayList<>();
            for (JsonNode node : root) {
                Entry entry = toEntry(node);
                if (entry != null) {
                    rows.add(entry);
                }
            }
            log.info("[knowledge] 知识库 mock 数据已加载 {} 条（{}）", rows.size(), path);
            return List.copyOf(rows);
        } catch (IOException ex) {
            log.warn("[knowledge] 知识库 mock 数据读取失败 {}：{}", path, ex.getClass().getSimpleName());
            return List.of();
        }
    }

    private Entry toEntry(JsonNode node) {
        String knowledgeId = text(node, "knowledgeId");
        if (knowledgeId == null) {
            return null;
        }
        String statusText = text(node, "status");
        KnowledgeItemStatus status = parseStatus(knowledgeId, statusText);
        String categoryText = text(node, "category");
        KnowledgeCategory category = parseCategory(knowledgeId, categoryText);
        JsonNode badcaseNode = node.get("badcase");
        int badcaseCount = badcaseNode != null && badcaseNode.isArray() ? badcaseNode.size() : 0;
        boolean badcase = badcaseNode != null
                && (badcaseNode.isArray() ? badcaseNode.size() > 0 : badcaseNode.asBoolean(false));
        return new Entry(
                knowledgeId,
                text(node, "title"),
                text(node, "content"),
                text(node, "version"),
                status,
                text(node, "owner"),
                // P1 兼容别名：effectiveDate → effectiveFrom
                firstNonNull(text(node, "effectiveFrom"), text(node, "effectiveDate")),
                // 兼容别名：expireDate → effectiveTo
                firstNonNull(text(node, "effectiveTo"), text(node, "expireDate")),
                source(node),
                category,
                badcase,
                badcaseCount,
                textArray(node, "refs"),
                textArray(node, "keywords"),
                text(node, "updatedAt"));
    }

    private static KnowledgeItemStatus parseStatus(String knowledgeId, String raw) {
        if (raw == null) {
            log.warn("[knowledge] {} 缺少 status，按「不可检索」处理", knowledgeId);
            return null;
        }
        try {
            return KnowledgeItemStatus.fromValue(raw.trim().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            log.warn("[knowledge] {} 的 status 不在契约枚举内（{}），按「不可检索」处理", knowledgeId, raw);
            return null;
        }
    }

    private static KnowledgeCategory parseCategory(String knowledgeId, String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return KnowledgeCategory.fromValue(raw.trim().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            log.warn("[knowledge] {} 的 category 不在契约枚举内（{}），按未分类处理", knowledgeId, raw);
            return null;
        }
    }

    private static KnowledgeSource source(JsonNode node) {
        JsonNode sourceNode = node.get("source");
        if (sourceNode == null || !sourceNode.isObject()) {
            return null;
        }
        String doc = text(sourceNode, "doc");
        if (doc == null) {
            return null;
        }
        return new KnowledgeSource(doc, text(sourceNode, "clause"), text(sourceNode, "page"));
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

    private static List<String> textArray(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isArray() || value.isEmpty()) {
            return null;
        }
        List<String> items = new ArrayList<>();
        for (JsonNode item : value) {
            String text = item.asText();
            if (text != null && !text.isBlank()) {
                items.add(text.trim());
            }
        }
        return items.isEmpty() ? null : List.copyOf(items);
    }

    private static String firstNonNull(String first, String second) {
        return first != null ? first : second;
    }
}
