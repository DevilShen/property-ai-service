/**
 * P2/P3 DTO（OpenAPI v1.1.0 契约同步：v0.3.0 W5 冻结 + v0.3.1 W6 增量 + v0.3.2 W7 增量 +
 * v0.3.3 W8 向后兼容增量 + v1.0.0 P2 冻结 + v1.1.0 P3 W10 向后兼容增量）。
 *
 * <p>本包只放与 {@code docs/openapi/openapi.yaml} 一一对应的 record / enum，不含 Controller / Service /
 * Repository；Controller 由 P2-008（bff/api/ticket 等）实现，Service 由 P2-007 领域服务实现。</p>
 *
 * <p>v0.3.1 增量（W6）：{@code DispatchPayload} / {@code CompletePayload} / {@code ErpWriteback}（+ 状态枚举）/
 * {@code TicketPush}（+ 通道/状态枚举）/ {@code TicketPushListResult} / {@code TicketFeedbackSummary} /
 * {@code TicketListSort}；{@code Ticket} / {@code TicketTransitionResult} / {@code TicketFeedbackResult}
 * 末尾追加可选字段并保留 W5 旧构造重载（新增字段 null 时不序列化，W5 响应形状不破坏）。</p>
 *
 * <p>v0.3.2 增量（W7）：{@code DataFreshness}（追加到 {@code RoomInfo}/{@code BillInfo} 末尾，保留旧构造重载）/
 * {@code RoomListResult}（+ {@code RoomListSort}）/ {@code BillListResult}（+ {@code BillListSort}）/
 * {@code MappingVersion} / {@code MappingField} / {@code MappingTrialRequest} / {@code MappingTrialReport} /
 * {@code MappingDiffItem}（+ 状态/差异枚举）/ {@code MappingRollbackRequest} /
 * {@code CapabilityState} / {@code CapabilityCode}（18 项）/ {@code CapabilityLevel} / {@code CapabilityStatus} /
 * {@code ConnectorSummary} / {@code ConnectorDetail}（+ 健康/生命周期枚举）/ {@code ProbeSummary} /
 * {@code ProbeReport} / {@code ProbeLatestResult} / {@code ProbeListResult} / {@code DegradeLedgerItem} /
 * {@code DegradeLedgerResult} / {@code ReconRunListResult}。</p>
 *
 * <p>v0.3.3 增量（W8）：{@code Actor}（+ {@code ActorRole}）/ {@code LoginRequest} / {@code LoginResult} /
 * {@code LogoutResult}（演示登录态，demo JWT）；{@code AuditEvent} / {@code AuditEventListResult}
 * （+ {@code AuditAction} / {@code AuditResourceType} / {@code AuditResult}，审计事件）。均为新增类型，
 * 不改既有 DTO 字段/构造，W5—W7 编译与响应形状不破坏。</p>
 *
 * <p>v1.1.0 增量（P3 W10）：费用（{@code ArrearsBucket}/{@code ArrearsBucketStat}/{@code ArrearsSummary}/
 * {@code ArrearsItem}/{@code ArrearsListResult}、{@code BillExplanation}（+ 明细/差异/引用））；
 * 催缴（{@code DunningStatus}/{@code DunningChannel}/{@code DunningTone}/{@code DunningFrequencyCheck}/
 * {@code DunningTask}/{@code DunningTaskListResult}/{@code DunningScript}（+ 请求）/ {@code DunningSendRequest}/
 * {@code DunningSendResult}/{@code DunningCloseRequest}/{@code DunningCloseResult}/{@code DunningFunnel}
 * （+ 转化率/时间窗））；投诉（{@code ComplaintLevel}/{@code ComplaintStatus}/{@code ComplaintTransitionAction}/
 * {@code ComplaintListSort}/{@code ComplaintAuditEntry}/{@code Complaint}/{@code ComplaintCreateRequest}/
 * {@code ComplaintTransitionRequest}/{@code ComplaintListResult}/{@code ComplaintStats}）；
 * 数据范围（{@code OwnerTicketCapability}）。{@code BillInfo} 末尾追加 {@code overdueDays/arrearsDays/
 * explainRef}、{@code Ticket} 末尾追加 {@code ownerId}（均保留旧构造重载）；{@code AuditAction}/
 * {@code AuditResourceType} 末尾追加 P3 取值。P2 端点请求/响应形状不破坏。</p>
 *
 * <p>约定：</p>
 * <ul>
 *   <li>字段名与 OpenAPI 同名；金额用 BigDecimal（定点两位，由 ERP 给出，AI 不自算）；</li>
 *   <li>枚举一律 {@code @JsonValue} 契约 code + {@code @JsonCreator fromValue}，禁止手写字符串；</li>
 *   <li>响应仍由 {@link com.property.ai.bff.api.ApiResponse} 包裹统一信封；</li>
 *   <li>同步规则：先改 OpenAPI，再同步本包与 packages/shared，然后跑 scripts/check-contract.py。</li>
 * </ul>
 */
package com.property.ai.bff.dto;
