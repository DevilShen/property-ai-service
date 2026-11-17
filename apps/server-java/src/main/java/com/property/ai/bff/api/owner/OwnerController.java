package com.property.ai.bff.api.owner;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.property.ai.bff.api.ApiResponse;
import com.property.ai.bff.api.ticket.ApiSupport;
import com.property.ai.bff.dto.DegradedFallback;
import com.property.ai.bff.dto.ErrorCode;
import com.property.ai.bff.dto.OwnerVerifyRequest;
import com.property.ai.bff.dto.OwnerVerifyResult;
import com.property.ai.connector.ConnectorException;
import com.property.ai.connector.ConnectorRuntime;
import com.property.ai.connector.ErpConnector;
import com.property.ai.domain.ticket.DomainException;
import com.property.ai.domain.ticket.TicketService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 业主身份核验（CAP-02 owner.verify）。
 *
 * <p>契约 {@code POST /api/v1/owner/verify}：
 * <ul>
 *   <li>核验失败返回 200 + {@code verified:false}（**不提示错因**），{@code attemptsLeft} 递减；</li>
 *   <li>连续 3 次失败/已锁定 → 403 E-2001；</li>
 *   <li>核验成功返回掩码姓名与项目名，5 分钟内在会话内复用（供建单前置校验）。</li>
 * </ul>
 *
 * <p>⚠️ P2 第 1 周 ERP 为 mock-erp 测试替身；真实短信/微信授权属 P2-030 之后范围，不在此实现。
 */
@RestController
@RequestMapping("/api/v1/owner")
public class OwnerController {

    private final ErpConnector connector;
    private final ConnectorRuntime runtime;
    private final TicketService ticketService;

    public OwnerController(ErpConnector connector, ConnectorRuntime runtime, TicketService ticketService) {
        this.connector = connector;
        this.runtime = runtime;
        this.ticketService = ticketService;
    }

    @PostMapping("/verify")
    public ResponseEntity<ApiResponse<OwnerVerifyResult>> verify(
            @RequestBody(required = false) OwnerVerifyRequest requestBody,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        String phoneTail = requestBody == null ? null : requestBody.phoneTail();
        if (phoneTail == null || !phoneTail.trim().matches("\\d{4}")) {
            throw DomainException.badRequest("请提供手机号后四位（4 位数字）",
                    Map.of("field", "phoneTail"));
        }
        String roomCode = requestBody.roomCode() == null || requestBody.roomCode().isBlank()
                ? null
                : ApiSupport.requireRoomCode(requestBody.roomCode());
        if (roomCode == null) {
            // mock-erp 不支持“仅按尾号在项目内匹配”，P2 第 1 周必须带 roomCode（已记入验收报告限制）
            throw DomainException.badRequest("请提供 roomCode（P2 第 1 周按房号 + 手机号后四位核验）",
                    Map.of("field", "roomCode"));
        }
        try {
            OwnerVerifyResult result = connector.verifyOwner(
                    new OwnerVerifyRequest(roomCode, phoneTail.trim()), traceId);
            if (result.locked()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(
                        ErrorCode.E_2001,
                        "核验次数已达上限，已为您转人工",
                        traceId,
                        Map.of("attemptsLeft", result.attemptsLeft() == null ? 0 : result.attemptsLeft(),
                                "roomCode", roomCode),
                        runtime.degraded("核验已锁定（连续失败 3 次）", DegradedFallback.HUMAN, false,
                                ErrorCode.E_2001, null),
                        false));
            }
            if (result.verified()) {
                ticketService.recordOwnerVerified(result.roomCode() == null || result.roomCode().isBlank()
                        ? roomCode
                        : result.roomCode());
            }
            return ResponseEntity.ok(ApiResponse.ok(result, traceId));
        } catch (ConnectorException ex) {
            if (ex.code() == ErrorCode.E_3001) {
                throw DomainException.notFound(ErrorCode.E_3001, "房号不存在：" + roomCode, Map.of("roomCode", roomCode));
            }
            return ResponseEntity.status(ApiSupport.statusFor(ex.code())).body(ApiResponse.error(
                    ex.code(),
                    "业主核验暂不可用（" + ex.getMessage() + "），可转人工核验",
                    traceId,
                    ex.details(),
                    runtime.degradedFromFailure(ex, DegradedFallback.HUMAN),
                    true));
        }
    }
}
