package com.property.ai.bff.api.knowledge;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.property.ai.bff.api.ApiResponse;
import com.property.ai.bff.api.ticket.ApiSupport;
import com.property.ai.bff.dto.Actor;
import com.property.ai.bff.dto.KnowledgeCommitResult;
import com.property.ai.bff.dto.KnowledgeImportCommitRequest;
import com.property.ai.bff.dto.KnowledgeImportCreateRequest;
import com.property.ai.bff.dto.KnowledgeImportJob;
import com.property.ai.bff.dto.KnowledgeImportJobListResult;
import com.property.ai.domain.knowledge.importer.KnowledgeImportException;
import com.property.ai.domain.knowledge.importer.KnowledgeImportService;
import com.property.ai.domain.project.ProjectService;
import com.property.ai.security.ActorContext;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 知识导入 REST API（task-86；契约 v1.5.0 <b>5 个 operation</b>）。
 *
 * <pre>
 * POST /api/v1/knowledge/imports                 201 创建导入任务（multipart 文件 或 JSON 粘贴文本）
 * GET  /api/v1/knowledge/imports                 200 任务列表（分页 + status 过滤；仅 actor 项目可见）
 * GET  /api/v1/knowledge/imports/{jobId}         200 任务详情（预览块/警告/重复/失败原因）
 * POST /api/v1/knowledge/imports/{jobId}/commit  200 确认预览并生成知识（默认 pending_review；approved 仅 it/admin 直审）
 * POST /api/v1/knowledge/imports/{jobId}/cancel  200 取消（pending/parsing/preview_ready；终态 409）
 * </pre>
 *
 * <h2>双形态入口（同一 path，靠 Content-Type 分支）</h2>
 * <ul>
 *   <li>{@code multipart/form-data}：{@code file} 必填（txt/md/docx/pdf；{@code sourceType=batch} 时 zip）
 *       + 可选 {@code title/projectId/category/sourceType}；</li>
 *   <li>{@code application/json}：{@link KnowledgeImportCreateRequest}（{@code title}+{@code content} 必填，
 *       {@code sourceType} 只允许 paste/badcase）。</li>
 * </ul>
 *
 * <h2>分层与安全</h2>
 * <ul>
 *   <li>角色 × 能力（{@code knowledge.import}、批量另需 {@code knowledge.import.batch}）由
 *       {@code SecurityFilter} + {@code PermissionMatrix} 统一执行；Controller 不写角色判断，
 *       直审（approved）的 it/admin 校验在 {@link KnowledgeImportService}（服务端强制）；</li>
 *   <li><b>项目作用域服务端强制</b>：可选 {@code ?projectId=} 由 {@code ProjectService#resolveScope} 解析
 *       （staff 跨项目 403 {@code E-2002}；缺省 = actor 项目；it/admin 缺省 = 平台级可见全部任务）；</li>
 *   <li>写审计：{@code commit} 由统一 {@code AuditFilter} 按矩阵登记落 {@code knowledge.import.commit}
 *       （resourceType=knowledge、resourceId=jobId）；创建任务不落独立写审计（契约口径）；
 *       {@code knowledge.index} 由导入服务在索引成功/失败时显式落库。</li>
 *   <li><b>限额/超频/编排不可用</b>：413 {@code E-1001} / 429 {@code E-4003} / 503 {@code E-5002}
 *       由 {@link KnowledgeImportExceptionHandler} 统一转错误信封。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/knowledge/imports")
public class KnowledgeImportController {

    /** 审计/错误 details 资源类型（与审计 resourceType=knowledge 同源） */
    static final String RESOURCE_TYPE = "knowledge";
    private static final int IDEMPOTENCY_KEY_MIN = 8;
    private static final int IDEMPOTENCY_KEY_MAX = 64;

    private final KnowledgeImportService importService;
    private final ProjectService projectService;

    public KnowledgeImportController(KnowledgeImportService importService, ProjectService projectService) {
        this.importService = importService;
        this.projectService = projectService;
    }

    // ==================================================================
    // POST /api/v1/knowledge/imports（multipart 文件上传）
    // ==================================================================

    /** 上传单文件或批量 ZIP（{@code multipart/form-data} 分支；201 + 同步预览态任务）。 */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<KnowledgeImportJob>> createMultipart(
            @RequestParam(name = "file", required = false) MultipartFile file,
            @RequestParam(name = "title", required = false) String title,
            @RequestParam(name = "projectId", required = false) String projectId,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "sourceType", required = false) String sourceType,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) throws IOException {
        String traceId = ApiSupport.traceId(request);
        if (file == null) {
            throw KnowledgeImportException.badRequest("multipart 上传必须提供 file 字段",
                    Map.of("field", "file", "required", true));
        }
        String idempotencyKeyValue = validateIdempotencyKey(idempotencyKey);
        Actor actor = ActorContext.actorOrNull();
        String scopeProjectId = projectService.resolveScope(projectId, RESOURCE_TYPE, traceId);
        byte[] content = file.getBytes();
        KnowledgeImportJob job = importService.createFile(file.getOriginalFilename(), content, title, category,
                sourceType, scopeProjectId, actor, idempotencyKeyValue, traceId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(job, traceId));
    }

    // ==================================================================
    // POST /api/v1/knowledge/imports（JSON 粘贴文本）
    // ==================================================================

    /** 粘贴文本 / RAG 缺口沉淀（{@code application/json} 分支；201 + 同步预览态任务）。 */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<KnowledgeImportJob>> createPaste(
            @RequestBody(required = false) KnowledgeImportCreateRequest requestBody,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        if (requestBody == null) {
            throw KnowledgeImportException.badRequest(
                    "请求体必填：{title, content}（粘贴文本）或改用 multipart/form-data 上传文件",
                    Map.of("body", "required"));
        }
        String idempotencyKeyValue = validateIdempotencyKey(idempotencyKey);
        Actor actor = ActorContext.actorOrNull();
        String scopeProjectId = projectService.resolveScope(requestBody.projectId(), RESOURCE_TYPE, traceId);
        KnowledgeImportJob job = importService.createPaste(requestBody, scopeProjectId, actor, idempotencyKeyValue,
                traceId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(job, traceId));
    }

    // ==================================================================
    // GET /api/v1/knowledge/imports
    // ==================================================================

    /** 导入任务列表（按 createdAt 倒序；仅返回 actor 项目可见任务；status 可选过滤）。 */
    @GetMapping
    public ResponseEntity<ApiResponse<KnowledgeImportJobListResult>> list(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "projectId", required = false) String projectId,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        Actor actor = ActorContext.actorOrNull();
        String scopeProjectId = projectService.resolveScope(projectId, RESOURCE_TYPE, traceId);
        KnowledgeImportJobListResult result = importService.list(scopeProjectId, status, page, pageSize, actor);
        return ResponseEntity.ok(ApiResponse.ok(result, traceId));
    }

    // ==================================================================
    // GET /api/v1/knowledge/imports/{jobId}
    // ==================================================================

    /** 导入任务详情（非本项目任务 → 404 E-3001，不区分无权限与不存在）。 */
    @GetMapping("/{jobId}")
    public ResponseEntity<ApiResponse<KnowledgeImportJob>> get(@PathVariable String jobId,
            @RequestParam(name = "projectId", required = false) String projectId,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        Actor actor = ActorContext.actorOrNull();
        String scopeProjectId = projectService.resolveScope(projectId, RESOURCE_TYPE, traceId);
        KnowledgeImportJob job = importService.get(jobId, scopeProjectId, actor);
        return ResponseEntity.ok(ApiResponse.ok(job, traceId));
    }

    // ==================================================================
    // POST /api/v1/knowledge/imports/{jobId}/commit
    // ==================================================================

    /** 确认预览并提交（默认 pending_review；approved 仅 it/admin 直审并即时索引）。 */
    @PostMapping("/{jobId}/commit")
    public ResponseEntity<ApiResponse<KnowledgeCommitResult>> commit(@PathVariable String jobId,
            @RequestBody(required = false) KnowledgeImportCommitRequest requestBody,
            @RequestParam(name = "projectId", required = false) String projectId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        String idempotencyKeyValue = validateIdempotencyKey(idempotencyKey);
        Actor actor = ActorContext.actorOrNull();
        String scopeProjectId = projectService.resolveScope(projectId, RESOURCE_TYPE, traceId);
        KnowledgeCommitResult result = importService.commit(jobId, requestBody, scopeProjectId, actor,
                idempotencyKeyValue, traceId);
        return ResponseEntity.ok(ApiResponse.ok(result, traceId));
    }

    // ==================================================================
    // POST /api/v1/knowledge/imports/{jobId}/cancel
    // ==================================================================

    /** 取消导入任务（pending/parsing/preview_ready；终态 409；不删除已归档原文）。 */
    @PostMapping("/{jobId}/cancel")
    public ResponseEntity<ApiResponse<KnowledgeImportJob>> cancel(@PathVariable String jobId,
            @RequestParam(name = "projectId", required = false) String projectId,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        Actor actor = ActorContext.actorOrNull();
        String scopeProjectId = projectService.resolveScope(projectId, RESOURCE_TYPE, traceId);
        KnowledgeImportJob job = importService.cancel(jobId, scopeProjectId, actor, traceId);
        return ResponseEntity.ok(ApiResponse.ok(job, traceId));
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /** 可选幂等键：缺省返回 null；给出时按契约写操作口径校验 8—64 字符（非法 400 E-1001）。 */
    private static String validateIdempotencyKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim();
        if (key.length() < IDEMPOTENCY_KEY_MIN || key.length() > IDEMPOTENCY_KEY_MAX) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("field", "Idempotency-Key");
            details.put("length", key.length());
            details.put("minimum", IDEMPOTENCY_KEY_MIN);
            details.put("maximum", IDEMPOTENCY_KEY_MAX);
            throw KnowledgeImportException.badRequest("Idempotency-Key 长度必须为 8—64 字符", details);
        }
        return key;
    }
}
