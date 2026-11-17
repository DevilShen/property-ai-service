package com.property.ai.domain.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.property.ai.connector.Capability;
import com.property.ai.connector.HttpErpClient;

/**
 * ERP 侧知识镜像对账（P4-005，私有协议 {@code GET /api/v1/meta/knowledge}）。
 *
 * <h2>边界（为什么只做「对账」不做「同步」）</h2>
 * 对外知识端点（{@code /api/v1/knowledge*}）由 Java 平台侧承载，事实源是
 * {@code mock/data/knowledge.json}（平台知识运营维护）；ERP 镜像只有<b>索引级字段</b>
 * （无 {@code content}/{@code source}），是「ERP 侧也有这份知识索引」的演示证据。
 * 因此本类<b>只比对索引字段、只记日志</b>：
 * <ul>
 *   <li><b>不反向覆盖</b>平台侧正文（AGENTS.md §1「不编造知识」）；</li>
 *   <li>镜像不可用/字段缺失时<b>不影响</b>对外接口（对账失败只 warn，读接口照常工作）；</li>
 *   <li>开关 {@code property.knowledge.erp-mirror-enabled} 默认 <b>false</b>（测试/离线环境不发出站请求），
 *       联调/演示时显式打开。</li>
 * </ul>
 *
 * <p>比对字段：{@code knowledgeId}（主键）/ {@code title} / {@code version} / {@code status} /
 * {@code owner} / {@code effectiveFrom} / {@code effectiveTo} / {@code category}。
 * 加载时机：{@link ApplicationReadyEvent}（仅开关打开时），结果留在 {@link #last()} 供诊断/测试读取。
 */
@Component
public class KnowledgeErpMirror {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeErpMirror.class);

    /** 镜像端点（mock-erp 私有协议，不进对外契约） */
    static final String MIRROR_PATH = "/api/v1/meta/knowledge";
    /** 一次取全量：44 条 << 上限 500 */
    private static final String MIRROR_PAGE_SIZE = "500";

    private final KnowledgeRepository repository;
    private final HttpErpClient httpErpClient;
    private final boolean enabled;
    private final AtomicReference<Reconciliation> last = new AtomicReference<>();

    public KnowledgeErpMirror(KnowledgeRepository repository, HttpErpClient httpErpClient,
            @Value("${property.knowledge.erp-mirror-enabled:false}") boolean enabled) {
        this.repository = repository;
        this.httpErpClient = httpErpClient;
        this.enabled = enabled;
    }

    /** 应用就绪后对账一次（开关打开才发出站请求；失败只 warn）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!enabled) {
            return;
        }
        reconcile("startup");
    }

    /** 最近一次对账结果（未对账过为空）。 */
    public Optional<Reconciliation> last() {
        return Optional.ofNullable(last.get());
    }

    /**
     * 拉 ERP 镜像并与本地知识索引对账（只读、只记日志）。
     *
     * @return 对账结果；ERP 不可用/响应非法时为空（不抛异常，不影响读接口）
     */
    public Optional<Reconciliation> reconcile(String traceId) {
        try {
            JsonNode envelope = httpErpClient.call(Capability.ROOM_QUERY, "GET", MIRROR_PATH,
                    Map.of("pageSize", MIRROR_PAGE_SIZE), null, Map.of(), traceId);
            Reconciliation result = compare(repository.all(), envelope);
            last.set(result);
            log.info("[knowledge] ERP 镜像对账 local={} mirror={} matched={} mismatch={} traceId={}",
                    result.localCount(), result.mirrorCount(), result.matched(), result.mismatches().size(),
                    traceId);
            if (!result.mismatches().isEmpty()) {
                log.warn("[knowledge] ERP 镜像索引不一致（只记日志，不覆盖平台正文）：{}", result.mismatches());
            }
            return Optional.of(result);
        } catch (RuntimeException ex) {
            log.warn("[knowledge] ERP 镜像对账跳过（ERP 不可用/未开通，不影响读接口）：{}",
                    ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * 索引级对账（纯函数，可单测）：{@code knowledgeId} 为主键，逐条比对索引字段。
     *
     * @param local    平台侧知识条目（{@link KnowledgeRepository.Entry}）
     * @param envelope ERP 镜像响应信封（{@code {ok,data:{items:[...],total}}}
     */
    static Reconciliation compare(List<KnowledgeRepository.Entry> local, JsonNode envelope) {
        Map<String, JsonNode> mirror = mirrorIndex(envelope);
        List<String> mismatches = new ArrayList<>();
        int matched = 0;
        Set<String> localIds = new LinkedHashSet<>();
        for (KnowledgeRepository.Entry entry : local) {
            localIds.add(entry.knowledgeId());
            JsonNode node = mirror.get(entry.knowledgeId());
            if (node == null) {
                mismatches.add(entry.knowledgeId() + ":missing_in_mirror");
                continue;
            }
            List<String> fields = new ArrayList<>();
            compareField(fields, "title", entry.title(), text(node, "title"));
            compareField(fields, "version", entry.version(), text(node, "version"));
            compareField(fields, "status", entry.status() == null ? null : entry.status().value(),
                    text(node, "status"));
            compareField(fields, "owner", entry.owner(), text(node, "owner"));
            compareField(fields, "effectiveFrom", entry.effectiveFrom(), text(node, "effectiveFrom"));
            compareField(fields, "effectiveTo", entry.effectiveTo(), text(node, "effectiveTo"));
            compareField(fields, "category", entry.category() == null ? null : entry.category().value(),
                    text(node, "category"));
            if (fields.isEmpty()) {
                matched++;
            } else {
                for (String field : fields) {
                    mismatches.add(entry.knowledgeId() + ":" + field);
                }
            }
        }
        for (String mirrorId : mirror.keySet()) {
            if (!localIds.contains(mirrorId)) {
                mismatches.add(mirrorId + ":extra_in_mirror");
            }
        }
        return new Reconciliation(local.size(), mirror.size(), matched, List.copyOf(mismatches));
    }

    private static Map<String, JsonNode> mirrorIndex(JsonNode envelope) {
        Map<String, JsonNode> index = new LinkedHashMap<>();
        JsonNode items = envelope == null ? null : envelope.path("data").path("items");
        if (items == null || !items.isArray()) {
            return index;
        }
        for (JsonNode item : items) {
            String id = text(item, "knowledgeId");
            if (id != null) {
                index.put(id, item);
            }
        }
        return index;
    }

    private static void compareField(List<String> differences, String field, String local, String mirror) {
        String left = normalize(local);
        String right = normalize(mirror);
        if (!left.equals(right)) {
            differences.add(field);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text.trim();
    }

    /** 对账结果：索引条数、一致条数、不一致明细（{@code knowledgeId:field}）。 */
    public record Reconciliation(int localCount, int mirrorCount, int matched, List<String> mismatches) {

        /** 是否完全一致。 */
        public boolean consistent() {
            return mismatches.isEmpty() && localCount == mirrorCount;
        }
    }
}
