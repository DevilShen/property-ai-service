package com.property.ai.domain.knowledge;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.property.ai.bff.dto.Actor;
import com.property.ai.bff.dto.ActorRole;
import com.property.ai.domain.project.ProjectService;

/**
 * 知识条目的演示项目可见范围（P5 W22 / P5-023「多项目知识/数据隔离」）。
 *
 * <h2>为什么知识需要项目作用域</h2>
 * 契约 v1.3.0 §7.6：所有业务数据带 {@code project_id} 语义，<b>服务层强制按项目过滤</b>，
 * 缺省 = 当前 actor 项目；跨项目 → 403 {@code E-2002} + {@code details{reason=cross_project}}。
 * 知识契约 DTO（{@code KnowledgeItem} 等）<b>没有</b> {@code projectId} 字段（不改契约），
 * 因此归属在服务端维护（进程内演示表，P5 迁 PG 后落 {@code knowledge.project_id} 列，接口不变）。
 *
 * <h2>演示口径（不是真实组织架构，AGENTS.md NEVER #11）</h2>
 * <ul>
 *   <li>{@code P-YQY} 云栖苑 = 集团主项目：<b>全量</b> 44 条（{@code mock/data/knowledge.json} 原样，不新增/不删除，
 *       保证 P4 知识与统计基线 44/36/4/2 不变）；</li>
 *   <li>{@code P-JZY} 金竹苑 / {@code P-LVY} 绿苑：各<b>继承</b> 2 条集团下发的演示知识
 *       （{@link #INHERITED}）——对应契约 §7.6「配置继承：集团 → 区域 → 项目」口径；</li>
 *   <li>其它项目（契约 {@code ProjectStatus=archived} 等）：无演示知识（空集合，不造数）。</li>
 * </ul>
 *
 * <h2>作用域语义</h2>
 * <ul>
 *   <li>{@code scopeProjectId == null}：平台级（it/admin 缺省）——不过滤，返回全量；</li>
 *   <li>{@code scopeProjectId = P-YQY}：全量 44 条；{@code P-JZY}/{@code P-LVY}：各自继承的 2 条；</li>
 *   <li>受限角色请求项目由 {@code domain/project/ProjectService#resolveScope} 先行校验
 *       （跨项目在 Controller 层就 403 并落 {@code access.deny} 审计）。</li>
 * </ul>
 */
public final class KnowledgeProjectScope {

    /**
     * 集团知识继承（演示）：{@code knowledgeId → 额外可见项目}。
     *
     * <p>只登记 P-JZY / P-LVY 各 2 条（P5 W22 任务卡「P-JZY/P-LVY 各 1—2 条演示数据」）；
     * 其余 40 条仅 P-YQY（集团主项目）可见。条目本身仍来自 {@code mock/data/knowledge.json}，
     * <b>不新建、不复制正文</b>（AGENTS.md §1 不编造知识）。
     */
    private static final Map<String, Set<String>> INHERITED = Map.of(
            "K-039", Set.of("P-JZY"),   // 身份核验失败替代路径（published）
            "K-040", Set.of("P-JZY"),   // 电梯困人救援口径（published）
            "K-037", Set.of("P-LVY"),   // 装修噪音投诉处理（published）
            "K-038", Set.of("P-LVY"));  // 投诉分级与首响口径（published）

    /**
     * 导入生成知识的项目归属（task-86）：{@code knowledgeId → projectId}。
     *
     * <p>契约知识 DTO 无 {@code projectId} 字段（不改契约），导入知识的归属只能服务端维护；
     * 与文件演示数据的「集团继承」不同，导入知识<b>只属于创建时的项目</b>（不跨项目继承，
     * 避免一个项目的私有材料被别的项目 RAG 召回）。P5 迁 PG 后落 {@code knowledge.project_id} 列。
     */
    private static final ConcurrentMap<String, String> IMPORTED = new ConcurrentHashMap<>();

    private KnowledgeProjectScope() {
    }

    /** 登记导入知识的项目归属（commit 生成知识时调用）。 */
    public static void registerImported(String knowledgeId, String projectId) {
        if (knowledgeId == null || knowledgeId.isBlank() || projectId == null || projectId.isBlank()) {
            return;
        }
        IMPORTED.put(knowledgeId.trim(), projectId.trim());
    }

    /** 撤销导入知识的项目登记（仅直审索引失败回滚用）。 */
    public static void unregisterImported(String knowledgeId) {
        if (knowledgeId != null && !knowledgeId.isBlank()) {
            IMPORTED.remove(knowledgeId.trim());
        }
    }

    /** 导入知识的项目归属（非导入知识/未登记返回 null）。 */
    public static String importedProjectOf(String knowledgeId) {
        return knowledgeId == null || knowledgeId.isBlank() ? null : IMPORTED.get(knowledgeId.trim());
    }

    /** 演示/测试重置（生产实现不调用）。 */
    public static void clearImportedForTest() {
        IMPORTED.clear();
    }

    /** 演示项目 ID（与 {@code ProjectService} / shared {@code DEMO_PROJECTS} 一致） */
    public static final String GROUP_PROJECT_ID = ProjectService.DEFAULT_PROJECT_ID;

    /** 条目是否在项目作用域内（{@code null}/空 = 平台级，全部可见；未知项目 = 空集合） */
    public static boolean visibleTo(String knowledgeId, String scopeProjectId) {
        if (scopeProjectId == null || scopeProjectId.isBlank()) {
            return true;
        }
        // 导入生成的知识：只属于创建时的项目（不继承、不跨项目）
        String importedProject = importedProjectOf(knowledgeId);
        if (importedProject != null) {
            return importedProject.equals(scopeProjectId);
        }
        if (GROUP_PROJECT_ID.equals(scopeProjectId)) {
            return true;
        }
        Set<String> inherited = INHERITED.get(knowledgeId);
        return inherited != null && inherited.contains(scopeProjectId);
    }

    /** 项目继承的演示知识 ID（诊断/测试/报告用；未知项目为空集合） */
    public static Set<String> inheritedKnowledgeIds(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return Set.of();
        }
        return INHERITED.entrySet().stream()
                .filter(entry -> entry.getValue().contains(projectId))
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * 主体缺省项目作用域（服务层兜底，Controller 已用 {@code ProjectService#resolveScope} 解析）：
     * 平台级（it/admin）→ {@code null}（全部）；受限角色 → {@code Actor.projectId}；
     * 无主体（离线演示/单测）→ {@code null}（不过滤，与既有单测口径一致）。
     */
    public static String scopeOf(Actor actor) {
        if (actor == null || actor.role() == null) {
            return null;
        }
        if (actor.role() == ActorRole.IT || actor.role() == ActorRole.ADMIN) {
            return null;
        }
        return actor.projectId() == null || actor.projectId().isBlank() ? null : actor.projectId();
    }
}
