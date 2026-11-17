package com.property.ai.bff.dto;

/**
 * OpenAPI: TicketPush（单条推送记录，v0.3.1 / P2 W6 推送占位）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.1 严格对齐；仅 DTO，不含业务逻辑。
 *
 * <p>产品设计 §5.1 建单/派单/完工三次推送，每次推送一条记录；P2 W6 由 {@code services/mock-channel}
 * 受理（{@code mock = true}，{@code status} 多为 {@code mocked}），不接真实通道。
 * {@code contentMasked} 只回脱敏正文（不回完整手机号/地址）。</p>
 */
public record TicketPush(
        /** 推送记录 ID（可回溯） */
        String pushId,
        String ticketNo,
        TicketPushChannel channel,
        /** 模板编码/名称（服务端给出，前端不硬编码） */
        String template,
        TicketPushStatus status,
        /** 受理/发出时间（pending 时为空） */
        String sentAt,
        /** 脱敏正文（手机号只留后四位、地址隐藏） */
        String contentMasked,
        /** P2 W6 推送为 mock 通道，恒为 true */
        boolean mock) {
}
