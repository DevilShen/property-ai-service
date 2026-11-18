package com.property.ai.bff.api.knowledge;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import com.property.ai.bff.api.ApiResponse;
import com.property.ai.bff.api.ticket.ApiSupport;
import com.property.ai.bff.dto.ErrorCode;
import com.property.ai.domain.knowledge.importer.KnowledgeImportException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 知识导入专用异常 → 契约错误信封（task-86，契约 v1.5.0 imports 5 端点）。
 *
 * <p>与全局 {@code TicketApiExceptionHandler} 并存但<b>只处理导入域更具体的异常类型</b>
 * （Spring 按异常类型最具体匹配，不影响既有端点）：
 * <ul>
 *   <li>{@link KnowledgeImportException}：400/403/404/409/413/429/503 的导入域语义（复用 17 类错误码）；</li>
 *   <li>{@link MaxUploadSizeExceededException}：Servlet 容器级 multipart 超限（>50MB 或请求 >64MB）
 *       → 413 {@code E-1001}（{@code details.reason=payload_too_large}，不落 500）；</li>
 *   <li>{@link MissingServletRequestPartException}/{@link MultipartException}：multipart 缺 part/格式非法
 *       → 400 {@code E-1001}；</li>
 *   <li>{@link HttpMediaTypeNotSupportedException}：非契约声明的 Content-Type → 415 {@code E-1001}
 *       （契约只声明 multipart 与 application/json 两分支）。</li>
 * </ul>
 */
@RestControllerAdvice
public class KnowledgeImportExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeImportExceptionHandler.class);

    /** 导入域业务失败（含 413/429/503；统一带 details/degraded/retryable）。 */
    @ExceptionHandler(KnowledgeImportException.class)
    public ResponseEntity<ApiResponse<Object>> handleImport(KnowledgeImportException exception,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        log.info("[knowledge-import] 业务失败 {} {} traceId={} details={}", exception.code().value(),
                exception.getMessage(), traceId, exception.details());
        return ResponseEntity.status(exception.httpStatus())
                .body(ApiResponse.error(exception.code(), exception.getMessage(), traceId, exception.details(),
                        exception.degraded(), exception.retryable()));
    }

    /** 容器 multipart 超限（file >50MB / request >64MB）→ 413 E-1001 载荷超限。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Object>> handleUploadTooLarge(MaxUploadSizeExceededException exception,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        log.info("[knowledge-import] multipart 超限 traceId={} msg={}", traceId, exception.getMessage());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", "payload_too_large");
        details.put("field", "file");
        details.put("hint", "单文件 ≤10MB；ZIP 批量 ≤50 文件/≤50MB");
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ApiResponse.error(ErrorCode.E_1001,
                "上传内容超限（单文件 ≤10MB；ZIP ≤50 文件/≤50MB）", traceId, details));
    }

    /** multipart 缺 part / 解析失败 → 400 E-1001（不落 500）。 */
    @ExceptionHandler({ MissingServletRequestPartException.class, MultipartException.class })
    public ResponseEntity<ApiResponse<Object>> handleMultipartException(Exception exception,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        log.info("[knowledge-import] multipart 非法 traceId={} err={}", traceId,
                exception.getClass().getSimpleName());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", "invalid_field");
        details.put("field", "file");
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.E_1001,
                "multipart 请求缺少 file 字段或格式非法", traceId, details));
    }
}
