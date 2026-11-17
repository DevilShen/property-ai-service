package com.property.ai.domain.project;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.property.ai.audit.AuditService;
import com.property.ai.bff.dto.Actor;
import com.property.ai.bff.dto.ActorRole;
import com.property.ai.bff.dto.AuditAction;
import com.property.ai.bff.dto.AuditResourceType;
import com.property.ai.bff.dto.AuditResult;
import com.property.ai.bff.dto.ErrorCode;
import com.property.ai.bff.dto.Project;
import com.property.ai.bff.dto.ProjectStatus;
import com.property.ai.domain.ticket.DomainException;
import com.property.ai.security.ActorContext;

/**
 * 项目隔离服务（P5-007 / task-62，契约 v1.3.0 {@code Project} / {@code GET /projects} / {@code ProjectId} 参数，
 * 架构 §24.7「多项目隔离 + 配置继承」）。
 *
 * <h2>演示项目（3 个，与 shared {@code DEMO_PROJECTS} / OpenAPI 示例逐字一致）</h2>
 * {@code P-YQY} 云栖苑（active，20 户，用友 NC）/ {@code P-JZY} 金竹苑（gray，18 户，金蝶 EAS）/
 * {@code P-LVY} 绿苑（active，16 户，用友 NC）。<b>演示数据，不是真实组织架构/租户</b>（AGENTS.md NEVER #11）。
 *
 * <h2>数据范围（服务端强制，前端隐藏入口不是安全边界）</h2>
 * <ul>
 *   <li>{@code owner}/{@code steward}/{@code agent}/{@code supervisor}：项目范围 = {@code Actor.projectId}
 *       （演示账号均 P-YQY）；缺省 {@code projectId} = 本项目；<b>跨项目 → 403 {@code E-2002}</b> +
 *       {@code details{reason=cross_project, resourceType, resourceId}}（复用既有错误码，不新造）；</li>
 *   <li>{@code it}/{@code admin}：平台级，缺省 = 全部项目，可显式跨项目读；</li>
 *   <li>拒绝落 {@code access.deny} 审计（资源类型 {@code project}，resourceId = 被请求的项目）。</li>
 * </ul>
 *
 * <h2>实现口径（快照式过滤）</h2>
 * 业务列表先把快照（ERP 只读结果 / 平台内存仓储）取出，再按 {@code projectId} 过滤（
 * {@link #filterByProject(List, String, Function)}）；项目不存在 → 404 {@code E-3001}，
 * {@code projectId} 非法 → 400 {@code E-1001}。P5 W22 接 PG 后改为 SQL 级 {@code project_id} 分区过滤，
 * 服务方法签名不变。
 */
@Service
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

    /** 默认项目（演示主项目 = 云栖苑；平台侧未带项目归属的记录按此归属，避免出现"无项目"脏数据） */
    public static final String DEFAULT_PROJECT_ID = "P-YQY";

    /** 项目 ID 格式：{@code P-XXX}（契约示例 P-YQY / P-JZY / P-LVY） */
    private static final Pattern PROJECT_ID_PATTERN = Pattern.compile("^P-[A-Z0-9]{2,8}$");

    /** 演示项目（顺序 = shared {@code DEMO_PROJECTS} / OpenAPI 示例顺序） */
    private static final List<Project> DEMO_PROJECTS = List.of(
            new Project("P-YQY", "云栖苑", ProjectStatus.ACTIVE, 20, "yonyou-nc-mock", "2026-09-01T09:00:00+08:00"),
            new Project("P-JZY", "金竹苑", ProjectStatus.GRAY, 18, "kingdee-eas", "2026-10-15T09:00:00+08:00"),
            new Project("P-LVY", "绿苑", ProjectStatus.ACTIVE, 16, "yonyou-nc-mock", "2026-11-01T09:00:00+08:00"));

    private final AuditService auditService;

    public ProjectService(AuditService auditService) {
        this.auditService = auditService;
    }

    // ------------------------------------------------------------------
    // 项目列表 / 详情
    // ------------------------------------------------------------------

    /** 全部演示项目（顺序固定；平台级视图/测试断言用） */
    public List<Project> all() {
        return DEMO_PROJECTS;
    }

    /**
     * 当前主体可见项目：{@code it}/{@code admin} 全部；staff/owner 仅本项目（未知项目 → 空，不造数）。
     *
     * <p>{@code actor == null}（鉴权关闭的离线演示/单测）按平台级返回全部（不对外宣称安全边界）。
     */
    public List<Project> visibleProjects(Actor actor) {
        if (actor == null || isPlatformRole(actor.role())) {
            return DEMO_PROJECTS;
        }
        String projectId = actor.projectId();
        Optional<Project> own = find(projectId);
        return own.map(List::of).orElse(List.of());
    }

    /** 按 ID 查演示项目（未注册返回 empty） */
    public Optional<Project> find(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return Optional.empty();
        }
        String normalized = projectId.trim();
        return DEMO_PROJECTS.stream().filter(item -> item.projectId().equals(normalized)).findFirst();
    }

    /** 项目详情（跨项目 403 {@code E-2002} {@code cross_project}；不存在 404 {@code E-3001}；非法 400） */
    public Project get(String projectId, String traceId) {
        Project project = requireProject(projectId);
        Actor actor = currentActor();
        if (isScoped(actor) && !project.projectId().equals(actor.projectId())) {
            throw denyCrossProject(actor, project.projectId(), "project", project.projectId(), traceId);
        }
        return project;
    }

    /** 项目校验（非法 → 400 E-1001；未注册 → 404 E-3001） */
    public Project requireProject(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw DomainException.badRequest("projectId 不能为空", Map.of("field", "projectId"));
        }
        String normalized = projectId.trim();
        if (!PROJECT_ID_PATTERN.matcher(normalized).matches()) {
            throw DomainException.badRequest("projectId 格式非法（如 P-YQY）",
                    Map.of("field", "projectId", "value", projectId));
        }
        return find(normalized).orElseThrow(() -> DomainException.notFound(ErrorCode.E_3001,
                "项目不存在：" + normalized, Map.of("projectId", normalized)));
    }

    // ------------------------------------------------------------------
    // 业务端点项目作用域（列表/详情 ?projectId=）
    // ------------------------------------------------------------------

    /**
     * 解析业务端点的项目作用域（{@code ?projectId=} 可选）。
     *
     * @param requestedProjectId 请求参数（省略 = 当前 actor 项目；平台级省略 = 全部）
     * @param resourceType       资源类型（审计/错误 details 用：room/bill/ticket/complaint/audit/event）
     * @param traceId            链路 ID
     * @return 生效项目 ID（{@code null} = 平台级未限定，不过滤）
     * @throws DomainException 400 参数非法 / 404 项目不存在 / 403 跨项目越权
     */
    public String resolveScope(String requestedProjectId, String resourceType, String traceId) {
        Actor actor = currentActor();
        if (requestedProjectId == null || requestedProjectId.isBlank()) {
            if (!isScoped(actor)) {
                return null;
            }
            String actorProject = actor.projectId();
            if (actorProject == null || actorProject.isBlank()) {
                return null;
            }
            // 演示账号项目必须存在于演示项目表（不一致属配置缺陷，按 404 暴露而不是静默放行）
            requireProject(actorProject);
            return actorProject;
        }
        Project requested = requireProject(requestedProjectId);
        if (isScoped(actor) && !requested.projectId().equals(actor.projectId())) {
            throw denyCrossProject(actor, requested.projectId(), resourceType, requested.projectId(), traceId);
        }
        return requested.projectId();
    }

    /**
     * 单条资源项目归属校验（详情端点用）：资源归属项目与生效作用域不一致 → 403 {@code cross_project}。
     *
     * @param scopeProjectId  {@link #resolveScope} 的结果（null = 平台级不过滤）
     * @param resourceProjectId 资源自身项目（null/空 = 无项目归属，不越权）
     */
    public void requireResourceProject(String scopeProjectId, String resourceProjectId, String resourceType,
            String resourceId, String traceId) {
        if (scopeProjectId == null || resourceProjectId == null || resourceProjectId.isBlank()) {
            return;
        }
        if (!scopeProjectId.equals(resourceProjectId)) {
            Actor actor = currentActor();
            throw denyCrossProject(actor, resourceProjectId, resourceType, resourceId, traceId);
        }
    }

    /**
     * 快照式项目过滤：{@code scopeProjectId == null} 原样返回；否则只保留归属项目一致的条目
     * （资源项目为空时按演示默认项目 {@link #DEFAULT_PROJECT_ID} 归属，避免跨项目脏数据混入）。
     */
    public <T> List<T> filterByProject(List<T> items, String scopeProjectId, Function<T, String> projectIdOf) {
        if (scopeProjectId == null || items == null || items.isEmpty()) {
            return items == null ? List.of() : items;
        }
        List<T> filtered = new ArrayList<>();
        for (T item : items) {
            String projectId = projectIdOf.apply(item);
            if (projectId == null || projectId.isBlank()) {
                projectId = DEFAULT_PROJECT_ID;
            }
            if (scopeProjectId.equals(projectId)) {
                filtered.add(item);
            }
        }
        return List.copyOf(filtered);
    }

    /**
     * 项目作用域分页（P5 W19 修复 task-64）：**先在全量条目上按项目过滤 → 计数 → 再按 {@code page/pageSize} 切片**。
     *
     * <p>修复前的实现是「先分页取一页 → 再按项目过滤 → 用过滤后条目数回填 {@code total}」，
     * 导致 {@code total} 恒等于本页条数（pageSize=3 → total=3；page=2 仍 total=3），前端真实分页失效。
     * 契约（v1.3.0 已冻结）{@code items/page/pageSize/total} 形状不变，仅 {@code total} 口径改为
     * 「当前过滤条件（含 {@code projectId}/{@code building}/{@code status} 等）下的真实命中总数」。
     *
     * @param allItems      已按业务过滤条件（building/status/…）筛过、未分页的全量快照
     * @param scopeProjectId 生效项目作用域（{@code null} = 平台级，不过滤）
     * @param projectIdOf   条目 → 归属项目
     * @param page           契约 page（≥1，调用方已校验）
     * @param pageSize       契约 pageSize（调用方已校验）
     * @return 本项目可见条目 + 过滤后总数（{@code total} 与本页条数无关）
     */
    public <T> ScopedPage<T> paginateByProject(List<T> allItems, String scopeProjectId,
            Function<T, String> projectIdOf, int page, int pageSize) {
        List<T> visible = filterByProject(allItems, scopeProjectId, projectIdOf);
        int total = visible.size();
        int safePageSize = Math.max(1, pageSize);
        long rawOffset = ((long) Math.max(1, page) - 1L) * (long) safePageSize;
        int from = (int) Math.min(rawOffset, (long) total);
        int to = (int) Math.min((long) from + (long) safePageSize, (long) total);
        return new ScopedPage<>(List.copyOf(visible.subList(from, to)), page, pageSize, total);
    }

    /**
     * 项目作用域分页结果（P5 W19 修复 task-64）：{@code total} = 过滤后的真实总数，
     * 与 {@code items.size()} 无关（可大于 {@code pageSize}）。
     */
    public record ScopedPage<T>(List<T> items, int page, int pageSize, int total) {
    }

    // ------------------------------------------------------------------
    // 角色 / 当前主体
    // ------------------------------------------------------------------

    /** 平台级角色（it/admin：跨项目可见） */
    public static boolean isPlatformRole(ActorRole role) {
        return role == ActorRole.IT || role == ActorRole.ADMIN;
    }

    /** 项目级受限主体（owner/staff：仅本项目；null（离线演示）按不限制处理） */
    public static boolean isScoped(Actor actor) {
        return actor != null && !isPlatformRole(actor.role());
    }

    /** 当前演示主体（鉴权过滤器写入；公开端点/单测为 null） */
    public Actor currentActor() {
        return ActorContext.actorOrNull();
    }

    /** 资源归属项目（空 → 演示默认项目；审计/事件/工单归属复用同一口径） */
    public static String projectOf(String projectId) {
        return projectId == null || projectId.isBlank() ? DEFAULT_PROJECT_ID : projectId;
    }

    // ------------------------------------------------------------------
    // 内部：越权拒绝 + 审计
    // ------------------------------------------------------------------

    /** 跨项目拒绝：落 {@code access.deny} 审计（资源类型 project）后抛 403 {@code E-2002} */
    private DomainException denyCrossProject(Actor actor, String projectId, String resourceType, String resourceId,
            String traceId) {
        if (actor != null) {
            try {
                auditService.record(new AuditService.AuditRecord(
                        actor.actorId(),
                        actor.role(),
                        AuditAction.ACCESS_DENY,
                        AuditResourceType.PROJECT,
                        resourceId == null ? "" : resourceId,
                        traceId,
                        AuditResult.DENIED,
                        null,
                        List.of()));
            } catch (RuntimeException ex) {
                log.warn("[project] access.deny 审计写入失败 projectId={} err={}", projectId,
                        ex.getClass().getSimpleName());
            }
        }
        log.info("[project] 跨项目拒绝 actor={} projectId={} resourceType={} resourceId={} traceId={}",
                actor == null ? "anonymous" : actor.actorId(), projectId, resourceType, resourceId, traceId);
        return DomainException.crossProject(resourceType, resourceId, projectId);
    }
}
