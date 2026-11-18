package com.property.ai.security;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.property.ai.bff.dto.ActorRole;
import com.property.ai.bff.dto.AuditAction;
import com.property.ai.bff.dto.AuditResourceType;
import com.property.ai.bff.dto.ComplaintTransitionAction;
import com.property.ai.bff.dto.OwnerTicketCapability;
import com.property.ai.bff.dto.TicketTransitionAction;

/**
 * 角色 × 能力矩阵 + 端点 → 能力映射（P2-030，契约 v0.3.3 §8 / 架构 §14.2）。
 *
 * <p>职责边界：<b>能力矩阵只在这里定义</b>，Controller 不写角色判断；鉴权由
 * {@link SecurityFilter} 统一执行。越权返回 403 {@code E-2001} +
 * {@code details{requiredCapability, role, reason=capability_denied}}（不新造错误码）。
 *
 * <h2>能力命名口径</h2>
 * 尽量复用契约 18 项 {@code CapabilityCode}（{@code room.query} / {@code bill.query} /
 * {@code owner.verify} / {@code ticket.create} / {@code ticket.status} / {@code ticket.assign} /
 * {@code visit.record} / {@code handoff.human}）；契约没有的能力（映射/连接器/审计/降级/对账）
 * 用平台权限标识（与 README §2.2「平台级权限用平台权限标识，不是错误码」一致），并与前端能力字典
 * （{@code apps/web/src/views/overview/data.ts} / {@code useConnectorWizard.ts}）同名。
 *
 * <h2>矩阵（demo 口径，逐条对齐 P2-030 任务卡 + P3 W13 修正）</h2>
 * <pre>
 * owner      : room.query bill.query owner.verify ticket.create ticket.status visit.record
 * steward    : room.query bill.query ticket.create ticket.status ticket.assign visit.record handoff.human
 * agent      : （同 steward）
 * supervisor : （同 steward）+ audit.read recon.run recon.report
 * it         : mapping.read mapping.trial mapping.publish mapping.rollback
 *              connector.read connector.probe degrade.read audit.read recon.run recon.report
 * admin      : （同 it）
 * </pre>
 *
 * <p><b>P3 W13 修正（task-43，契约 v1.1.0 §7.2 说明性修正，OpenAPI 形状/版本不变）</b>：
 * {@code ticket.create} 从「仅 owner」放宽到 <b>steward / agent / supervisor</b>（staff 代业主报修，
 * 项目内任意房号，归属回填 {@code owner-<roomCode>}）；owner 仍可建单但仅限本户房号，
 * 跨房 403 {@code E-2002} {@code out_of_scope}；it/admin 仍 403 {@code E-2001}。
 * 角色级能力只决定「能不能调用」，房号范围为数据级权限，由 {@code TicketService} 强制。
 *
 * <p>P3 W12 投诉域增量（契约 v1.1.0 §7.2）：
 * <pre>
 * owner      : + complaint.create complaint.read（均限本户，数据范围由领域服务强制）
 * steward    : + complaint.create complaint.read complaint.handle complaint.stats
 * agent      : （同 steward）
 * supervisor : + complaint.create complaint.read complaint.handle complaint.escalate complaint.stats
 * it / admin : 无投诉域能力（403 E-2001；access.deny 审计 resourceType=complaint）
 * </pre>
 *
 * <p>P3 W11 催缴域增量（契约 v1.1.0 §7.2）：
 * <pre>
 * steward / agent / supervisor : + dunning.task（列表/详情/话术/人工确认发送/关闭/漏斗）
 * owner / it / admin           : 无催缴能力（403 E-2001；access.deny 审计 resourceType=dunning）
 * </pre>
 *
 * <p><b>数据范围（自有房/自有工单）不在本矩阵</b>：房号校验沿用既有服务层（owner.verify 前置），
 * 工单「仅本人」过滤需要契约新增房间维度筛选参数（P3 范围），W8 只做角色级能力鉴权，
 * 已在交付报告列为遗留风险。
 *
 * <p>P3 W13 增量（P3-028/029/031/032，契约 v1.1.0）：
 * <pre>
 * owner      : + ticket.reschedule.own ticket.urge.own（仅本户自有单；数据范围由 TicketService 强制）
 * transition 端点级门槛从 ticket.assign 改为 ticket.status（owner/staff 均放行，
 *            动作级差异 + 数据范围在 TicketService 二次校验；it/admin 仍 403）
 * + GET /api/v1/recon/report/{runId}/export → recon.report（supervisor/it/admin）
 *   + 落 recon.export 审计（resourceType=export，resourceId=runId）
 * it/admin   : + fee.catalog.change / fee.catalog.review（P3-032 服务层能力；端点见下条）
 * </pre>
 *
 * <p>P4 W18 补口（task-56，契约 v1.2.0 已声明、Java REST 补实现）：
 * <pre>
 * GET  /api/v1/fees/catalog/changes                     → fee.catalog.change（仅 it/admin；staff/owner 403）
 * POST /api/v1/fees/catalog/changes                     → fee.catalog.change（提交 pending_review）
 * POST /api/v1/fees/catalog/changes/{changeId}/review   → fee.catalog.review（仅 it/admin；自审 403 由领域服务判定）
 * 审计：由 domain/fee/FeeCatalogChangeService 落 fee.catalog.change / fee.catalog.review
 *       （resourceType=fee_catalog、resourceId=changeId），端点规则 auditAction 留 null 以避免双写。
 * </pre>
 *
 * <p>P4 W15 知识域增量（P4-005/006，契约 v1.2.0 §7.4）：
 * <pre>
 * owner                        : + knowledge.read（**仅 published + 生效期**，其余 404 不暴露）
 * steward / agent / supervisor : + knowledge.read knowledge.change knowledge.stats（全部状态可读）
 * it / admin                   : + knowledge.read knowledge.change knowledge.review knowledge.stats
 * knowledge.review             : **仅 it/admin**（staff/owner 403；且提交人不得自审 → 403）
 * </pre>
 *
 * <p>P4 W16—W17 增量（P4-011/012/013/018，契约 v1.2.0 §7.4）：
 * <pre>
 * steward            : + copilot.use copilot.broadcast（群发必须 confirmed:true）
 * agent              : + copilot.use copilot.broadcast
 *                      + desk.queue desk.takeover desk.release desk.sla desk.qa.score（只看/评自己）
 * supervisor         : + copilot.use copilot.broadcast
 *                      + desk.queue desk.takeover desk.release desk.sla desk.qa.score desk.qa.stats
 *                      + trace.read trace.export
 * it / admin         : + copilot.use（平台只读）
 *                      + desk.queue desk.sla desk.qa.score desk.qa.stats（读视图；不含接管/释放）
 *                      + trace.read trace.export
 * owner              : 无 Copilot/坐席/回溯能力（403 E-2001）
 * </pre>
 *
 * <p>P5 W20 增量（task-65 / P5-020，契约 v1.3.0 未声明的内部演示端点，P5-024 契约补口）：
 * <pre>
 * GET /api/v1/observability/metrics → observability.read（仅 it/admin）
 * GET /api/v1/observability/health  → observability.read（仅 it/admin）
 * owner/staff/agent/supervisor → 403 E-2001；无 token → 401 E-2001（由 SecurityFilter 统一执行）
 * </pre>
 *
 * <p>P6 W30 RAG 增量（task-80，契约 v1.4.0 §7.8）：
 * <pre>
 * POST /api/v1/rag/query                        → rag.query（owner/steward/agent/supervisor/it/admin 六角色）
 * GET  /api/v1/rag/health                       → rag.health（**仅 it/admin**）
 * POST /api/v1/knowledge/{knowledgeId}/reindex  → knowledge.reindex（**仅 it/admin**）
 * </pre>
 * <ul>
 *   <li>{@code rag.query} 六角色均可调用，<b>数据范围不同</b>：owner 本户/本项目知识、staff 本项目、
 *       it/admin 平台级；{@code projectId} 由服务端 {@code ProjectService#resolveScope} 强制
 *       （跨项目 → 403 {@code E-2002} {@code cross_project}）；</li>
 *   <li>问答为<b>只读语义</b>（契约 v1.4.0 {@code ragQuery} 明确「不写业务主数据、不落写审计」），
 *       故端点规则 {@code auditAction} 留 null；观测元数据（queryHash/answerable/cites/mode/latencyMs）
 *       由 {@code com.property.ai.audit.RagQueryAuditLog} 承载（{@code AuditAction} 枚举无 {@code rag.query}
 *       取值，且 {@code bff/dto} 为冻结契约产物，不新增取值）；</li>
 *   <li>重建索引是<b>写操作</b>：统一 {@code AuditFilter} 落 {@code knowledge.reindex}
 *       （{@code resourceType=knowledge}、{@code resourceId} 取路径 knowledgeId），失败尝试同样留痕。</li>
 * </ul>
 */
public final class PermissionMatrix {

    // ------------------------------------------------------------------
    // 能力名（与契约 CapabilityCode / 前端能力字典一致；平台级权限不是错误码）
    // ------------------------------------------------------------------

    /** CAP-01 房档只读 */
    public static final String ROOM_QUERY = "room.query";
    /** CAP-03 账单只读 */
    public static final String BILL_QUERY = "bill.query";
    /** CAP-02 业主身份核验 */
    public static final String OWNER_VERIFY = "owner.verify";
    /** CAP-06 报修建单 */
    public static final String TICKET_CREATE = "ticket.create";
    /** CAP-08 工单进度/列表/详情/推送记录 */
    public static final String TICKET_STATUS = "ticket.status";
    /** CAP-07/09 工单派单/改派/迁移（复合动作，取前端可读的 ticket.assign） */
    public static final String TICKET_ASSIGN = "ticket.assign";
    /** CAP-13 回访记录 */
    public static final String VISIT_RECORD = "visit.record";
    /** CAP-18 转人工摘要 */
    public static final String HANDOFF_HUMAN = "handoff.human";

    // ---------------- P3-029 owner 自有单专用能力（契约 v1.1.0 §7.2 / OwnerTicketCapability） ----------------
    //
    // 与契约枚举 OwnerTicketCapability 同源（不手写第二份字符串）：owner 仅能用 reschedule / urge，
    // 且只作用于**本户自有单**；staff 仍用 ticket.assign（见 ticketTransitionCapability）。

    /** owner 对本户自有单改约（transition action=reschedule；契约 v1.1.0 增量） */
    public static final String TICKET_RESCHEDULE_OWN = OwnerTicketCapability.TICKET_RESCHEDULE_OWN.value();
    /** owner 对本户自有单催办（transition action=urge；契约 v1.1.0 增量） */
    public static final String TICKET_URGE_OWN = OwnerTicketCapability.TICKET_URGE_OWN.value();
    /** 映射只读（平台级） */
    public static final String MAPPING_READ = "mapping.read";
    /** 映射试跑（平台级） */
    public static final String MAPPING_TRIAL = "mapping.trial";
    /** 映射发布（平台级） */
    public static final String MAPPING_PUBLISH = "mapping.publish";
    /** 映射回滚（平台级） */
    public static final String MAPPING_ROLLBACK = "mapping.rollback";
    /** 连接器只读（平台级） */
    public static final String CONNECTOR_READ = "connector.read";
    /** 能力探测（平台级；P2-025 probe） */
    public static final String CONNECTOR_PROBE = "connector.probe";
    /** 降级台账只读（平台级） */
    public static final String DEGRADE_READ = "degrade.read";
    /** 只读对账触发（P2-013/P2-027） */
    public static final String RECON_RUN = "recon.run";
    /** 对账报告/历史只读 */
    public static final String RECON_REPORT = "recon.report";
    /** 审计读取（平台级权限标识，契约 README §2.2 明确不是错误码） */
    public static final String AUDIT_READ = "audit.read";

    // ---------------- P3 W12 投诉域能力（契约 v1.1.0 §7.2；平台级权限标识，不是错误码） ----------------

    /** 提交投诉：owner 仅本户 / steward / agent / supervisor（it/admin 403） */
    public static final String COMPLAINT_CREATE = "complaint.create";
    /** 投诉只读（列表/详情）：owner 仅本户 / steward / agent / supervisor（it/admin 403） */
    public static final String COMPLAINT_READ = "complaint.read";
    /** 投诉处理类动作（accept / first_response / close）：仅 staff（owner 403） */
    public static final String COMPLAINT_HANDLE = "complaint.handle";
    /** 投诉升级（escalate）：**仅 supervisor**（L1/L2 上报红线） */
    public static final String COMPLAINT_ESCALATE = "complaint.escalate";
    /**
     * 投诉统计看板（GET /complaints/stats）：仅 staff。
     *
     * <p>契约 README §7.2 把该行标为「（`complaint.read`）owner ❌」，但同一张表又要求 owner 对
     * 列表/详情可用 `complaint.read`；两者不能共用一个能力码（否则 owner 要么看不了本户投诉、
     * 要么能看管理看板）。故按「统计是管理视图」拆出独立能力码，角色结果与 §7.2 表一致。
     */
    public static final String COMPLAINT_STATS = "complaint.stats";

    // ---------------- P3 W11 催缴域能力（契约 v1.1.0 §7.2；平台级权限标识，不是错误码） ----------------

    /**
     * 催缴任务（列表/详情/话术/人工确认发送/关闭/漏斗）：<b>steward / agent / supervisor</b>；
     * owner / it / admin → 403 {@code E-2001}（{@code details.requiredCapability=dunning.task}）。
     *
     * <p>契约 §7.2 逐行：
     * <ul>
     *   <li>{@code GET /dunning/tasks*}、{@code /dunning/funnel}：staff 本片区/本项目；owner/it/admin ❌；</li>
     *   <li>{@code POST .../script}（生成话术≠外发）、{@code .../send}（必须 {@code confirmed:true} +
     *       频次红线 409 {@code E-3003}）、{@code .../close}：同一能力码；</li>
     * </ul>
     * 「本片区」细化（楼栋/片区维度）随数据范围任务落地（P3-028），本文件只做角色级能力判定。
     */
    public static final String DUNNING_TASK = "dunning.task";

    // ---------------- P3-032 口径变更卡点（平台级权限标识） ----------------
    //
    // 契约 v1.2.0 已声明 GET/POST /api/v1/fees/catalog/changes + POST .../{changeId}/review（§7.4：
    // 仅 it/admin）；P4 W18 补口在 buildRules() 登记端点规则，本处能力码仍用于领域服务二次校验。

    /** 提交费项口径变更（it/admin）：进入 pending_review，不生效 */
    public static final String FEE_CATALOG_CHANGE = "fee.catalog.change";
    /** 审核费项口径变更（it/admin，且不得自审）：通过才生效到 FeeDictionary */
    public static final String FEE_CATALOG_REVIEW = "fee.catalog.review";

    // ---------------- P4 W15 知识域能力（契约 v1.2.0 §7.4；平台级权限标识，不是错误码） ----------------

    /**
     * 知识只读（列表/详情/版本/检索）：<b>owner</b> 仅 published 且在生效期内（服务端强制过滤，
     * 非 published → 404 不暴露）；<b>steward/agent/supervisor</b> 全部状态；<b>it/admin</b> 平台只读。
     *
     * <p>契约 §7.4 逐行：owner 读 published；管理视图才看 draft/pending_review/rejected；
     * {@code /knowledge/search} 对全部 6 角色开放但只回 published+生效期。
     */
    public static final String KNOWLEDGE_READ = "knowledge.read";
    /** 提交知识变更（staff + it/admin；owner ❌ 403）：提交后 pending_review，不直接生效 */
    public static final String KNOWLEDGE_CHANGE = "knowledge.change";
    /** 审核知识变更（<b>仅 it/admin</b>；staff/owner ❌ 403，且提交人不得自审）：通过生成新版本并生效 */
    public static final String KNOWLEDGE_REVIEW = "knowledge.review";
    /** 知识统计（采纳率/缺口/过期/责任人，管理视图）：staff + it/admin；owner ❌ 403 */
    public static final String KNOWLEDGE_STATS = "knowledge.stats";

    // ---------------- P4 W16—W17 Copilot / 坐席 / 回溯能力（契约 v1.2.0 §7.4；平台级权限标识） ----------------

    /**
     * Copilot 8 工作区（workspaces/summary/script/portrait/digest/report）：<b>staff + it/admin</b>；
     * owner ❌ 403 {@code E-2001}（{@code copilot.use}）。
     *
     * <p>契约 §7.4 逐行：steward 限本片区、agent/supervisor 限本项目、it/admin 平台只读；
     * 结果一律 {@code mock:true}（演示编排，未接真实模型；模型调用归 Python 编排层）。
     */
    public static final String COPILOT_USE = "copilot.use";
    /**
     * 群服务人工确认群发：<b>仅 staff</b>（steward/agent/supervisor）；owner/it/admin ❌ 403。
     *
     * <p>红线（AGENTS.md NEVER #4/#5）：{@code confirmed:true} 必须；只经 mock 通道留痕；
     * <b>不做群内自动回复</b>；审计 {@code copilot.broadcast}（资源类型 {@code conversation}）。
     */
    public static final String COPILOT_BROADCAST = "copilot.broadcast";

    /** 坐席队列（GET /desk/queue）：agent/supervisor + it/admin 平台只读；steward/owner ❌ 403 */
    public static final String DESK_QUEUE = "desk.queue";
    /** 接管会话（POST /desk/queue/{id}/takeover）：仅 agent/supervisor；互斥 409 {@code E-3002} */
    public static final String DESK_TAKEOVER = "desk.takeover";
    /** 释放会话（POST /desk/queue/{id}/release）：仅 agent/supervisor（非本人接管 → 403） */
    public static final String DESK_RELEASE = "desk.release";
    /** 坐席 SLA（GET /desk/sla）：agent/supervisor + it/admin 平台只读；steward/owner ❌ 403 */
    public static final String DESK_SLA = "desk.sla";
    /**
     * 质检（GET /desk/qa/samples、POST /desk/qa/samples/{id}/score）：agent（只看/评自己）/ supervisor /
     * it/admin；评分 0—100，重复评分 409 {@code E-3002}，审计 {@code desk.qa.score}。
     */
    public static final String DESK_QA_SCORE = "desk.qa.score";
    /** 质检统计（GET /desk/qa/stats，管理视图）：**仅 supervisor/it/admin**；agent ❌ 403 */
    public static final String DESK_QA_STATS = "desk.qa.stats";

    /** 全链路回溯详情/检索（GET /trace/{traceId}、/trace/search）：**仅 supervisor/it/admin** */
    public static final String TRACE_READ = "trace.read";
    /** 证据包导出（GET /trace/{traceId}/evidence，json/csv）：supervisor/it/admin；落 {@code trace.export} 审计 */
    public static final String TRACE_EXPORT = "trace.export";

    // ---------------- P5 W19 项目隔离 / 连接器 SDK / 灰度 / 事件（契约 v1.3.0 §7.6；平台级权限标识） ----------------

    /**
     * 项目只读（{@code GET /projects}、{@code GET /projects/{projectId}}）：<b>全部 6 角色</b>；
     * 数据范围不同——owner/staff 仅本项目（{@code Actor.projectId}），it/admin 平台级全部项目；
     * 跨项目 → 403 {@code E-2002} {@code cross_project}（服务层强制）。
     */
    public static final String PROJECT_READ = "project.read";
    /**
     * 连接器 SDK 声明读取（{@code GET /connectors/{connectorId}/sdk}）：<b>仅 it/admin</b>（平台集成视图，
     * 与 {@link #CONNECTOR_READ} 同角色）；owner/staff → 403 {@code E-2001}。
     */
    public static final String CONNECTOR_SDK_READ = "connector.sdk.read";
    /**
     * 灰度策略读取（{@code GET /gray/policies} + {@code GET /gray/compare}）：<b>仅 it/admin</b>
     * （灰度是平台发布视图，不做项目级细分；P5 W22 配置继承后再细粒度）。
     */
    public static final String GRAY_POLICY_READ = "gray.policy.read";
    /** 灰度策略管理（{@code POST /gray/policies}、{@code PATCH /gray/policies/{policyId}}）：<b>仅 it/admin</b> */
    public static final String GRAY_POLICY_MANAGE = "gray.policy.manage";
    /**
     * 事件读取（{@code GET /events}）：supervisor（本项目）+ it/admin（平台）；owner/staff/agent ❌ 403。
     * outbox 事件含跨系统投递信息，属运维/管理视图（含 {@code dead} 死信可查）。
     */
    public static final String EVENT_READ = "event.read";
    /**
     * 可观测性读取（P5 W20 / task-65，{@code GET /api/v1/observability/metrics} 与
     * {@code GET /api/v1/observability/health}）：<b>仅 it/admin</b>（平台运维视图）。
     *
     * <p><b>内部演示端点：契约 v1.3.0 未声明本组端点，P5-024 契约补口</b>；owner/staff/agent/supervisor
     * → 403 {@code E-2001}（{@code details.requiredCapability=observability.read}），无 token → 401。
     * 指标含连接器健康/缓存/事件积压等运维信息，不下放给业务角色（数据最小化）。
     */
    public static final String OBSERVABILITY_READ = "observability.read";

    // ---------------- P6 W30 RAG 能力（契约 v1.4.0 §7.8；平台级权限标识，不是错误码） ----------------

    /**
     * 知识问答（{@code POST /api/v1/rag/query}）：<b>全部 6 角色</b>；数据范围不同——owner 本户/本项目知识、
     * steward/agent/supervisor 本项目（工作台/Copilot 使用）、it/admin 平台级。
     *
     * <p>数据范围由 {@code ProjectService#resolveScope} + {@code KnowledgeProjectScope} 强制，
     * 跨项目 → 403 {@code E-2002} {@code cross_project}；问答本身只读（不落写审计，见类注释）。
     */
    public static final String RAG_QUERY = "rag.query";
    /**
     * RAG 编排健康（{@code GET /api/v1/rag/health}）：<b>仅 it/admin</b>（平台运维视图，含模型/向量库信息）；
     * owner/staff/supervisor → 403 {@code E-2001}（{@code details.requiredCapability=rag.health}）。
     */
    public static final String RAG_HEALTH = "rag.health";
    /**
     * 知识重建索引（{@code POST /api/v1/knowledge/{knowledgeId}/reindex}）：<b>仅 it/admin</b>（写操作）；
     * 落审计 {@code knowledge.reindex}（契约 v1.4.0 AuditAction 末尾追加取值）。
     */
    public static final String KNOWLEDGE_REINDEX = "knowledge.reindex";

    // ---------------- P6 W34 知识导入能力（契约 v1.5.0 §7.9；平台级权限标识，不是错误码） ----------------

    /**
     * 知识导入（{@code POST/GET /api/v1/knowledge/imports*}）：<b>steward / agent / supervisor / it / admin</b>；
     * owner ❌ 403 {@code E-2001}（{@code details.requiredCapability=knowledge.import}）。
     *
     * <p>数据范围：steward/agent/supervisor 仅本项目（跨项目 403 {@code E-2002} {@code cross_project}）；
     * it/admin 平台级（跨项目可见）；非本项目任务对外 404 {@code E-3001}。
     * 创建任务不落独立写审计；{@code commit} 落 {@code knowledge.import.commit}。
     */
    public static final String KNOWLEDGE_IMPORT = "knowledge.import";
    /**
     * 知识批量导入（ZIP，{@code sourceType=batch}）：<b>steward / agent / supervisor / it / admin</b>；
     * 缺该能力 → 403 {@code E-2001}（{@code details.requiredCapability=knowledge.import.batch}）。
     * 限额：ZIP ≤50 文件 / 解压总量 ≤50MB（超限 413 {@code E-1001}）。
     */
    public static final String KNOWLEDGE_IMPORT_BATCH = "knowledge.import.batch";

    private static final Map<ActorRole, Set<String>> MATRIX;
    private static final List<EndpointRule> RULES;

    static {
        MATRIX = buildMatrix();
        RULES = buildRules();
    }

    private PermissionMatrix() {
    }

    // ------------------------------------------------------------------
    // 矩阵查询
    // ------------------------------------------------------------------

    /** 角色能力集合（不可变；未知角色返回空集）。 */
    public static Set<String> capabilities(ActorRole role) {
        if (role == null) {
            return Set.of();
        }
        return MATRIX.getOrDefault(role, Set.of());
    }

    /** 角色是否具备某能力。 */
    public static boolean allows(ActorRole role, String capability) {
        if (capability == null || capability.isBlank()) {
            return true;
        }
        return capabilities(role).contains(capability);
    }

    /** 全量矩阵（只读；用于报告/测试断言）。 */
    public static Map<ActorRole, Set<String>> all() {
        return MATRIX;
    }

    // ------------------------------------------------------------------
    // 端点策略
    // ------------------------------------------------------------------

    /** 按 HTTP 方法 + 路径查找端点策略（未注册端点返回空 = 仅要求已登录，不额外限制能力）。 */
    public static Optional<EndpointRule> ruleFor(String method, String path) {
        if (method == null || path == null) {
            return Optional.empty();
        }
        String normalizedMethod = method.toUpperCase(Locale.ROOT);
        String normalizedPath = normalizePath(path);
        for (EndpointRule rule : RULES) {
            if (rule.method().equals(normalizedMethod) && rule.matches(normalizedPath)) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }

    /** 公开端点（契约 v0.3.3 §8：`/healthz`、12 屏 mock、`POST /api/v1/auth/login`，演示豁免不鉴权）。 */
    public static boolean isPublic(String method, String path) {
        return ruleFor(method, path).map(EndpointRule::publicEndpoint).orElse(false);
    }

    /** 路径归一：去掉 query（防御性）、重复斜杠与尾斜杠。 */
    public static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String value = path.trim();
        int queryIndex = value.indexOf('?');
        if (queryIndex >= 0) {
            value = value.substring(0, queryIndex);
        }
        value = value.replaceAll("/{2,}", "/");
        if (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    // ------------------------------------------------------------------
    // 构建
    // ------------------------------------------------------------------

    private static Map<ActorRole, Set<String>> buildMatrix() {
        EnumMap<ActorRole, Set<String>> matrix = new EnumMap<>(ActorRole.class);

        Set<String> owner = capabilitySet(
                ROOM_QUERY, BILL_QUERY, OWNER_VERIFY, TICKET_CREATE, TICKET_STATUS, VISIT_RECORD,
                TICKET_RESCHEDULE_OWN, TICKET_URGE_OWN,
                COMPLAINT_CREATE, COMPLAINT_READ,
                KNOWLEDGE_READ, PROJECT_READ, RAG_QUERY);
        Set<String> steward = capabilitySet(
                ROOM_QUERY, BILL_QUERY, TICKET_CREATE, TICKET_STATUS, TICKET_ASSIGN, VISIT_RECORD, HANDOFF_HUMAN,
                COMPLAINT_CREATE, COMPLAINT_READ, COMPLAINT_HANDLE, COMPLAINT_STATS,
                DUNNING_TASK,
                KNOWLEDGE_READ, KNOWLEDGE_CHANGE, KNOWLEDGE_STATS,
                KNOWLEDGE_IMPORT, KNOWLEDGE_IMPORT_BATCH,
                COPILOT_USE, COPILOT_BROADCAST,
                PROJECT_READ, RAG_QUERY);
        Set<String> agent = capabilitySet(
                ROOM_QUERY, BILL_QUERY, TICKET_CREATE, TICKET_STATUS, TICKET_ASSIGN, VISIT_RECORD, HANDOFF_HUMAN,
                COMPLAINT_CREATE, COMPLAINT_READ, COMPLAINT_HANDLE, COMPLAINT_STATS,
                DUNNING_TASK,
                KNOWLEDGE_READ, KNOWLEDGE_CHANGE, KNOWLEDGE_STATS,
                KNOWLEDGE_IMPORT, KNOWLEDGE_IMPORT_BATCH,
                COPILOT_USE, COPILOT_BROADCAST,
                DESK_QUEUE, DESK_TAKEOVER, DESK_RELEASE, DESK_SLA, DESK_QA_SCORE,
                PROJECT_READ, RAG_QUERY);
        Set<String> supervisor = capabilitySet(
                ROOM_QUERY, BILL_QUERY, TICKET_CREATE, TICKET_STATUS, TICKET_ASSIGN, VISIT_RECORD, HANDOFF_HUMAN,
                AUDIT_READ, RECON_RUN, RECON_REPORT,
                COMPLAINT_CREATE, COMPLAINT_READ, COMPLAINT_HANDLE, COMPLAINT_ESCALATE, COMPLAINT_STATS,
                DUNNING_TASK,
                KNOWLEDGE_READ, KNOWLEDGE_CHANGE, KNOWLEDGE_STATS,
                KNOWLEDGE_IMPORT, KNOWLEDGE_IMPORT_BATCH,
                COPILOT_USE, COPILOT_BROADCAST,
                DESK_QUEUE, DESK_TAKEOVER, DESK_RELEASE, DESK_SLA, DESK_QA_SCORE, DESK_QA_STATS,
                TRACE_READ, TRACE_EXPORT,
                PROJECT_READ, EVENT_READ, RAG_QUERY);
        Set<String> platform = capabilitySet(
                MAPPING_READ, MAPPING_TRIAL, MAPPING_PUBLISH, MAPPING_ROLLBACK,
                CONNECTOR_READ, CONNECTOR_PROBE, DEGRADE_READ, AUDIT_READ, RECON_RUN, RECON_REPORT,
                FEE_CATALOG_CHANGE, FEE_CATALOG_REVIEW,
                KNOWLEDGE_READ, KNOWLEDGE_CHANGE, KNOWLEDGE_REVIEW, KNOWLEDGE_STATS, KNOWLEDGE_REINDEX,
                KNOWLEDGE_IMPORT, KNOWLEDGE_IMPORT_BATCH,
                COPILOT_USE,
                DESK_QUEUE, DESK_SLA, DESK_QA_SCORE, DESK_QA_STATS,
                TRACE_READ, TRACE_EXPORT,
                PROJECT_READ, CONNECTOR_SDK_READ, GRAY_POLICY_READ, GRAY_POLICY_MANAGE, EVENT_READ,
                OBSERVABILITY_READ, RAG_QUERY, RAG_HEALTH);

        matrix.put(ActorRole.OWNER, owner);
        matrix.put(ActorRole.STEWARD, steward);
        matrix.put(ActorRole.AGENT, agent);
        matrix.put(ActorRole.SUPERVISOR, supervisor);
        matrix.put(ActorRole.IT, platform);
        matrix.put(ActorRole.ADMIN, platform);
        return Collections.unmodifiableMap(matrix);
    }

    private static Set<String> capabilitySet(String... capabilities) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(List.of(capabilities)));
    }

    private static List<EndpointRule> buildRules() {
        return List.of(
                // ---------------- 公开端点（demo 演示豁免，不鉴权） ----------------
                rule("GET", "/healthz", null, null, null, true),
                rule("GET", "/api/v1/meta/screens", null, null, null, true),
                rule("GET", "/api/v1/screens/{screenKey}", null, null, null, true),
                rule("POST", "/api/v1/auth/login", null, null, null, true),

                // ---------------- 登录态自身端点（需 token，无能力门槛） ----------------
                rule("GET", "/api/v1/auth/me", null, null, AuditResourceType.AUTH, false),
                rule("POST", "/api/v1/auth/logout", null, null, AuditResourceType.AUTH, false),

                // ---------------- 业主域（房档/账单/核验） ----------------
                rule("POST", "/api/v1/owner/verify", OWNER_VERIFY, AuditAction.OWNER_VERIFY,
                        AuditResourceType.OWNER, false, "ownerName", "phoneTail"),
                rule("GET", "/api/v1/rooms/{roomCode}", ROOM_QUERY, null, AuditResourceType.OWNER, false),
                rule("GET", "/api/v1/rooms", ROOM_QUERY, null, AuditResourceType.OWNER, false),
                rule("GET", "/api/v1/bills", BILL_QUERY, null, AuditResourceType.OWNER, false),

                // ---------------- 工单闭环 ----------------
                rule("POST", "/api/v1/tickets", TICKET_CREATE, AuditAction.TICKET_CREATE,
                        AuditResourceType.TICKET, false, "phoneTail"),
                // P3-029（契约 v1.1.0 §7.2）：端点级门槛用 ticket.status（owner/staff 均有；it/admin 403），
                // 动作级差异在 TicketService 二次校验：owner 仅 reschedule/urge 且限本户自有单
                // （ticket.reschedule.own / ticket.urge.own），staff 仍需 ticket.assign。
                rule("POST", "/api/v1/tickets/{ticketNo}/transition", TICKET_STATUS,
                        AuditAction.TICKET_TRANSITION, AuditResourceType.TICKET, false),
                rule("POST", "/api/v1/tickets/{ticketNo}/feedback", VISIT_RECORD,
                        AuditAction.TICKET_FEEDBACK, AuditResourceType.TICKET, false, "phoneTail"),
                rule("GET", "/api/v1/tickets/{ticketNo}/handoff-summary", HANDOFF_HUMAN, null,
                        AuditResourceType.TICKET, false),
                rule("GET", "/api/v1/tickets/{ticketNo}/pushes", TICKET_STATUS, null,
                        AuditResourceType.TICKET, false),
                rule("GET", "/api/v1/tickets/{ticketNo}", TICKET_STATUS, null, AuditResourceType.TICKET, false),
                rule("GET", "/api/v1/tickets", TICKET_STATUS, null, AuditResourceType.TICKET, false),

                // ---------------- 映射版本化（IT/管理员） ----------------
                rule("POST", "/api/v1/mappings/{mappingId}/trial", MAPPING_TRIAL, null,
                        AuditResourceType.MAPPING, false),
                rule("POST", "/api/v1/mappings/{mappingId}/publish", MAPPING_PUBLISH,
                        AuditAction.MAPPING_PUBLISH, AuditResourceType.MAPPING, false),
                rule("POST", "/api/v1/mappings/{mappingId}/rollback", MAPPING_ROLLBACK,
                        AuditAction.MAPPING_ROLLBACK, AuditResourceType.MAPPING, false),
                rule("GET", "/api/v1/mappings/{mappingId}", MAPPING_READ, null, AuditResourceType.MAPPING, false),
                rule("GET", "/api/v1/mappings", MAPPING_READ, null, AuditResourceType.MAPPING, false),

                // ---------------- 连接器/能力探测/降级（IT/管理员） ----------------
                rule("POST", "/api/v1/connectors/{connectorId}/probe", CONNECTOR_PROBE,
                        AuditAction.CONNECTOR_PROBE, AuditResourceType.CONNECTOR, false),
                rule("GET", "/api/v1/connectors/{connectorId}/probe/latest", CONNECTOR_READ, null,
                        AuditResourceType.CONNECTOR, false),
                rule("GET", "/api/v1/connectors/{connectorId}/probes", CONNECTOR_READ, null,
                        AuditResourceType.CONNECTOR, false),
                rule("GET", "/api/v1/connectors/{connectorId}", CONNECTOR_READ, null,
                        AuditResourceType.CONNECTOR, false),
                rule("GET", "/api/v1/connectors", CONNECTOR_READ, null, AuditResourceType.CONNECTOR, false),
                rule("GET", "/api/v1/degrade/ledger", DEGRADE_READ, null, AuditResourceType.CONNECTOR, false),

                // ---------------- 只读对账（主管/IT/管理员） ----------------
                rule("POST", "/api/v1/recon/run", RECON_RUN, AuditAction.RECON_RUN, AuditResourceType.RECON, false),
                // P3-031 对账 CSV 导出（契约 v1.1.0 reconReportExport）：2xx 为 text/csv 文件流（非 JSON 信封），
                // 错误仍走 ErrorResponse；导出是敏感动作 → 落 recon.export 审计（resourceType=export，
                // resourceId=runId 由路径动态段取；由 AuditFilter 统一记账，Controller 不重复写）。
                rule("GET", "/api/v1/recon/report/{runId}/export", RECON_REPORT, AuditAction.RECON_EXPORT,
                        AuditResourceType.EXPORT, false),
                rule("GET", "/api/v1/recon/report/{runId}", RECON_REPORT, null, AuditResourceType.RECON, false),
                rule("GET", "/api/v1/recon/runs", RECON_REPORT, null, AuditResourceType.RECON, false),

                // ---------------- 投诉（P3-021…023，契约 v1.1.0 §7.2） ----------------
                // 写审计由 ComplaintService 显式落 complaint.create/complaint.transition（resource=complaint:{id}），
                // 故这里 auditAction 留 null，避免 AuditFilter 与领域服务双写同一动作。
                rule("POST", "/api/v1/complaints", COMPLAINT_CREATE, null, AuditResourceType.COMPLAINT, false,
                        "contactTail4", "content"),
                rule("GET", "/api/v1/complaints", COMPLAINT_READ, null, AuditResourceType.COMPLAINT, false),
                rule("GET", "/api/v1/complaints/stats", COMPLAINT_STATS, null, AuditResourceType.COMPLAINT, false),
                rule("GET", "/api/v1/complaints/{complaintId}", COMPLAINT_READ, null,
                        AuditResourceType.COMPLAINT, false),
                // transition 的端点级门槛用 complaint.read（owner 需能重开本户）：
                // 处理类动作 complaint.handle / 升级 complaint.escalate 在领域服务按具体 action 二次校验。
                rule("POST", "/api/v1/complaints/{complaintId}/transition", COMPLAINT_READ, null,
                        AuditResourceType.COMPLAINT, false),

                // ---------------- 催缴（P3-011…015，契约 v1.1.0 §7.2） ----------------
                // 能力 dunning.task（steward/agent/supervisor；owner/it/admin 403 E-2001）。
                // 写审计由 DunningTaskService 显式落 dunning.script/send/close（resource=dunning:{taskId}），
                // 故这里 auditAction 留 null，避免 AuditFilter 与领域服务双写同一动作。
                rule("GET", "/api/v1/dunning/tasks", DUNNING_TASK, null, AuditResourceType.DUNNING, false),
                rule("GET", "/api/v1/dunning/tasks/{taskId}", DUNNING_TASK, null, AuditResourceType.DUNNING, false),
                rule("POST", "/api/v1/dunning/tasks/{taskId}/script", DUNNING_TASK, null,
                        AuditResourceType.DUNNING, false, "content", "ownerNameMasked"),
                rule("POST", "/api/v1/dunning/tasks/{taskId}/send", DUNNING_TASK, null,
                        AuditResourceType.DUNNING, false, "ownerNameMasked", "content"),
                rule("POST", "/api/v1/dunning/tasks/{taskId}/close", DUNNING_TASK, null,
                        AuditResourceType.DUNNING, false, "reason"),
                rule("GET", "/api/v1/dunning/funnel", DUNNING_TASK, null, AuditResourceType.DUNNING, false),

                // ---------------- 知识（P4-005/006，契约 v1.2.0 §7.4） ----------------
                // 路径顺序约束（契约 §1.5 / README §7.5）：/knowledge/search、/knowledge/stats 是**字面量段**，
                // 必须排在 /knowledge/{knowledgeId} 之前（本矩阵按注册顺序匹配，变量段正则会吞掉 search/stats）。
                // 写审计：knowledge.change / knowledge.review（resourceType=knowledge、resourceId=knowledgeId
                // 由路径动态段取值、maskedFields 只记字段名），统一由 AuditFilter 落库，领域服务不重复写。

                // ---------------- 知识导入（P6 W34 / task-86，契约 v1.5.0 §7.9） ----------------
                // **顺序约束**：字面量段 /knowledge/imports* 必须排在 /knowledge/{knowledgeId} 之前，
                // 否则 GET /knowledge/imports 会被当成 knowledgeId 而 404/403。
                // 能力：knowledge.import（steward/agent/supervisor/it/admin；owner 403 E-2001）；
                //       批量 ZIP 的 knowledge.import.batch 在服务层按 sourceType=batch 二次校验（同角色集合，
                //       端点级无法从 multipart 字段预判，故不登记为端点能力）。
                // 审计：创建任务不落独立写审计（契约口径）；commit 落 knowledge.import.commit
                //       （resourceType=knowledge、resourceId=jobId 由路径动态段取）；
                //       knowledge.index 由导入服务在索引成功/失败时显式落库（避免双写）。
                rule("POST", "/api/v1/knowledge/imports", KNOWLEDGE_IMPORT, null, AuditResourceType.KNOWLEDGE,
                        false),
                rule("GET", "/api/v1/knowledge/imports", KNOWLEDGE_IMPORT, null, AuditResourceType.KNOWLEDGE,
                        false),
                rule("GET", "/api/v1/knowledge/imports/{jobId}", KNOWLEDGE_IMPORT, null,
                        AuditResourceType.KNOWLEDGE, false),
                rule("POST", "/api/v1/knowledge/imports/{jobId}/commit", KNOWLEDGE_IMPORT,
                        AuditAction.KNOWLEDGE_IMPORT_COMMIT, AuditResourceType.KNOWLEDGE, false,
                        "title", "owner", "reviewNote"),
                rule("POST", "/api/v1/knowledge/imports/{jobId}/cancel", KNOWLEDGE_IMPORT, null,
                        AuditResourceType.KNOWLEDGE, false),

                rule("GET", "/api/v1/knowledge/search", KNOWLEDGE_READ, null, AuditResourceType.KNOWLEDGE, false),
                rule("GET", "/api/v1/knowledge/stats", KNOWLEDGE_STATS, null, AuditResourceType.KNOWLEDGE, false),
                rule("POST", "/api/v1/knowledge/{knowledgeId}/change", KNOWLEDGE_CHANGE,
                        AuditAction.KNOWLEDGE_CHANGE, AuditResourceType.KNOWLEDGE, false,
                        "title", "content", "reason"),
                rule("POST", "/api/v1/knowledge/{knowledgeId}/review", KNOWLEDGE_REVIEW,
                        AuditAction.KNOWLEDGE_REVIEW, AuditResourceType.KNOWLEDGE, false, "note"),
                rule("GET", "/api/v1/knowledge/{knowledgeId}/versions", KNOWLEDGE_READ, null,
                        AuditResourceType.KNOWLEDGE, false),
                rule("GET", "/api/v1/knowledge/{knowledgeId}", KNOWLEDGE_READ, null,
                        AuditResourceType.KNOWLEDGE, false),
                rule("GET", "/api/v1/knowledge", KNOWLEDGE_READ, null, AuditResourceType.KNOWLEDGE, false),

                // ---------------- RAG 问答 / 健康 / 重建索引（P6 W30，契约 v1.4.0 §7.8） ----------------
                // 1) 问答：六角色均可（rag.query）；数据范围（本项目/平台级、跨项目 403 cross_project）由
                //    domain/project/ProjectService 在 Controller 层强制。契约明确「只读语义、不落写审计」，
                //    故 auditAction 留 null；观测元数据由 audit/RagQueryAuditLog 承载（不落问题/答案正文）。
                rule("POST", "/api/v1/rag/query", RAG_QUERY, null, AuditResourceType.AUTH, false),
                // 2) 健康：仅 it/admin（rag.health；平台运维视图）。只读端点不落写审计。
                rule("GET", "/api/v1/rag/health", RAG_HEALTH, null, AuditResourceType.AUTH, false),
                // 3) 重建索引：仅 it/admin（knowledge.reindex）——**写操作**，统一 AuditFilter 落
                //    knowledge.reindex（resourceType=knowledge、resourceId 取路径动态段 = knowledgeId，
                //    响应 data.knowledgeId 也在 RESOURCE_ID_FIELDS 之外，由路径兜底）；失败尝试同样留痕。
                rule("POST", "/api/v1/knowledge/{knowledgeId}/reindex", KNOWLEDGE_REINDEX,
                        AuditAction.KNOWLEDGE_REINDEX, AuditResourceType.KNOWLEDGE, false),

                // ---------------- Copilot 8 工作区（P4-011，契约 v1.2.0 §7.4） ----------------
                // 能力 copilot.use（steward/agent/supervisor + it/admin；owner 403）。
                // summary/script 是「生成类 POST」但**不触达业主**（只生成不外发），契约未要求写审计；
                // 唯一写操作 = 群服务人工确认群发（copilot.broadcast，落审计，资源类型 conversation）。
                rule("GET", "/api/v1/copilot/workspaces", COPILOT_USE, null, AuditResourceType.CONVERSATION, false),
                rule("POST", "/api/v1/copilot/summary", COPILOT_USE, null, AuditResourceType.CONVERSATION, false),
                rule("POST", "/api/v1/copilot/script", COPILOT_USE, null, AuditResourceType.CONVERSATION, false),
                rule("GET", "/api/v1/copilot/portrait", COPILOT_USE, null, AuditResourceType.OWNER, false),
                rule("GET", "/api/v1/copilot/digest", COPILOT_USE, null, AuditResourceType.CONVERSATION, false),
                rule("GET", "/api/v1/copilot/report", COPILOT_USE, null, AuditResourceType.CONVERSATION, false),
                rule("POST", "/api/v1/copilot/group/broadcast", COPILOT_BROADCAST,
                        AuditAction.COPILOT_BROADCAST, AuditResourceType.CONVERSATION, false,
                        "content", "targetRooms"),

                // ---------------- 坐席队列 / 质检（P4-012/013，契约 v1.2.0 §7.4） ----------------
                // 路径顺序（契约 §1.5）：/desk/qa/samples 是字面量段，须先于 /desk/qa/samples/{sampleId}/score
                // 之外没有变量歧义；接管/释放的 resourceId 取路径 conversationId（审计资源类型 conversation）。
                // 审计：desk.takeover / desk.qa.score（释放无独立审计动作枚举，不双写其它动作）。
                rule("GET", "/api/v1/desk/queue", DESK_QUEUE, null, AuditResourceType.CONVERSATION, false),
                rule("GET", "/api/v1/desk/sla", DESK_SLA, null, AuditResourceType.CONVERSATION, false),
                rule("GET", "/api/v1/desk/qa/stats", DESK_QA_STATS, null, AuditResourceType.CONVERSATION, false),
                rule("GET", "/api/v1/desk/qa/samples", DESK_QA_SCORE, null, AuditResourceType.CONVERSATION, false),
                rule("POST", "/api/v1/desk/qa/samples/{sampleId}/score", DESK_QA_SCORE,
                        AuditAction.DESK_QA_SCORE, AuditResourceType.CONVERSATION, false, "note"),
                rule("POST", "/api/v1/desk/queue/{conversationId}/takeover", DESK_TAKEOVER,
                        AuditAction.DESK_TAKEOVER, AuditResourceType.CONVERSATION, false, "note"),
                rule("POST", "/api/v1/desk/queue/{conversationId}/release", DESK_RELEASE, null,
                        AuditResourceType.CONVERSATION, false),

                // ---------------- 全链路回溯（P4-018，契约 v1.2.0 §7.4） ----------------
                // 路径顺序（契约 §1.5 明确要求）：字面量段 /trace/search 必须先于 /trace/{traceId} 匹配；
                // evidence 是 {traceId} + 字面量段，同样先于 /trace/{traceId}（否则会被当成 traceId 的一部分而 404）。
                rule("GET", "/api/v1/trace/search", TRACE_READ, null, AuditResourceType.TRACE, false),
                rule("GET", "/api/v1/trace/{traceId}/evidence", TRACE_EXPORT, AuditAction.TRACE_EXPORT,
                        AuditResourceType.EXPORT, false, "ownerPhone", "ownerName", "roomCode"),
                rule("GET", "/api/v1/trace/{traceId}", TRACE_READ, null, AuditResourceType.TRACE, false),

                // ---------------- 费项口径变更（P4 W18 补口，契约 v1.2.0 §7.4；仅 it/admin） ----------------
                // 能力：列表/提交 fee.catalog.change、审核 fee.catalog.review（staff/owner 403 E-2001）。
                // 审计：状态机内由 domain/fee/FeeCatalogChangeService 落 fee.catalog.change / fee.catalog.review
                // （resourceType=fee_catalog、resourceId=changeId，审核失败也留痕），故这里 auditAction 留 null，
                // 避免 AuditFilter 与服务层双写同一动作。
                rule("GET", "/api/v1/fees/catalog/changes", FEE_CATALOG_CHANGE, null,
                        AuditResourceType.FEE_CATALOG, false),
                rule("POST", "/api/v1/fees/catalog/changes", FEE_CATALOG_CHANGE, null,
                        AuditResourceType.FEE_CATALOG, false, "newFormula", "reason"),
                rule("POST", "/api/v1/fees/catalog/changes/{changeId}/review", FEE_CATALOG_REVIEW, null,
                        AuditResourceType.FEE_CATALOG, false, "note"),

                // ---------------- 审计（it/admin/supervisor） ----------------
                rule("GET", "/api/v1/audit/events/{eventId}", AUDIT_READ, null, AuditResourceType.AUTH, false),
                rule("GET", "/api/v1/audit/events", AUDIT_READ, null, AuditResourceType.AUTH, false),

                // ---------------- P5 W19 增量（契约 v1.3.0 §7.6）：项目隔离 / 连接器 SDK / 灰度 / 事件 ----------------
                // 路径顺序约束：字面量段先于变量段（本矩阵按注册顺序匹配）。
                // 1) 项目隔离：全部 6 角色可读 project.read；数据范围（本项目 vs 平台级 vs 跨项目 403
                //    cross_project）由 domain/project/ProjectService 强制，拒绝落 access.deny（resourceType=project）。
                rule("GET", "/api/v1/projects", PROJECT_READ, null, AuditResourceType.PROJECT, false),
                rule("GET", "/api/v1/projects/{projectId}", PROJECT_READ, null, AuditResourceType.PROJECT, false),
                // 2) 连接器 SDK 声明：仅 it/admin（connector.sdk.read；与 connector.read 同角色）
                rule("GET", "/api/v1/connectors/{connectorId}/sdk", CONNECTOR_SDK_READ, null,
                        AuditResourceType.CONNECTOR, false),
                // 3) 灰度：读（列表/双跑）gray.policy.read、管理（创建/更新）gray.policy.manage，均仅 it/admin；
                //    写审计由 domain/gray/GrayService 显式落 gray.policy.create/update（resourceType=gray_policy），
                //    故这里 auditAction 留 null 避免 AuditFilter 双写。
                rule("GET", "/api/v1/gray/policies", GRAY_POLICY_READ, null, AuditResourceType.GRAY_POLICY, false),
                rule("POST", "/api/v1/gray/policies", GRAY_POLICY_MANAGE, null,
                        AuditResourceType.GRAY_POLICY, false),
                rule("PATCH", "/api/v1/gray/policies/{policyId}", GRAY_POLICY_MANAGE, null,
                        AuditResourceType.GRAY_POLICY, false),
                rule("GET", "/api/v1/gray/compare", GRAY_POLICY_READ, null, AuditResourceType.GRAY_POLICY, false),
                // 4) 事件（outbox 只读）：supervisor 本项目 + it/admin 平台；只读端点不落 AuditEvent。
                rule("GET", "/api/v1/events", EVENT_READ, null, AuditResourceType.AUTH, false),

                // ---------------- P5 W20 增量（task-65 / P5-020）：可观测性（内部演示端点，P5-024 契约补口） ----------------
                // 契约 v1.3.0 未声明 /api/v1/observability/*；能力 observability.read 仅 it/admin（平台运维视图），
                // 只读端点不落写审计；owner/staff/agent/supervisor → 403 E-2001（resourceType=auth）。
                rule("GET", "/api/v1/observability/metrics", OBSERVABILITY_READ, null, AuditResourceType.AUTH, false),
                rule("GET", "/api/v1/observability/health", OBSERVABILITY_READ, null, AuditResourceType.AUTH, false));
    }

    /**
     * 投诉 transition 的<b>动作级</b>能力（P3-023，契约 §7.2 逐行）：
     * <ul>
     *   <li>{@code accept} / {@code first_response} / {@code close} → {@link #COMPLAINT_HANDLE}（staff）；</li>
     *   <li>{@code escalate} → {@link #COMPLAINT_ESCALATE}（仅 supervisor）；</li>
     *   <li>{@code reopen} → {@link #COMPLAINT_READ}（owner 本户 / staff；本户数据范围由领域服务强制）。</li>
     * </ul>
     * 端点级门槛（{@link #COMPLAINT_READ}）先行拦截无投诉域能力的角色（it/admin），
     * 动作级差异由领域服务用本方法二次校验。
     */
    public static String complaintTransitionCapability(ComplaintTransitionAction action) {
        if (action == null) {
            return COMPLAINT_READ;
        }
        return switch (action) {
            case ACCEPT, FIRST_RESPONSE, CLOSE -> COMPLAINT_HANDLE;
            case ESCALATE -> COMPLAINT_ESCALATE;
            case REOPEN -> COMPLAINT_READ;
        };
    }

    /**
     * 工单 transition 的<b>动作级</b>能力（P3-029，契约 §7.2 逐行）：
     * <ul>
     *   <li>{@code reschedule} → {@link #TICKET_RESCHEDULE_OWN}（owner 本户自有单）/ staff 的 {@link #TICKET_ASSIGN}；</li>
     *   <li>{@code urge} → {@link #TICKET_URGE_OWN}（owner 本户自有单）/ staff 的 {@link #TICKET_ASSIGN}；</li>
     *   <li>{@code dispatch} / {@code on_site} / {@code complete} → {@link #TICKET_ASSIGN}（owner 403）。</li>
     * </ul>
     * 端点级门槛（{@link #TICKET_STATUS}）先拦截无工单域能力的角色（it/admin），
     * owner/staff 的动作级差异与数据范围由 {@code TicketService} 用本方法二次校验。
     */
    public static String ticketTransitionCapability(TicketTransitionAction action, boolean ownerActor) {
        if (!ownerActor) {
            return TICKET_ASSIGN;
        }
        if (action == TicketTransitionAction.RESCHEDULE) {
            return TICKET_RESCHEDULE_OWN;
        }
        if (action == TicketTransitionAction.URGE) {
            return TICKET_URGE_OWN;
        }
        // owner 的其它动作在契约里仍以 ticket.assign 作为 details.requiredCapability（§7.2 / transition 描述）
        return TICKET_ASSIGN;
    }

    private static EndpointRule rule(String method, String template, String requiredCapability,
            AuditAction auditAction, AuditResourceType resourceType, boolean publicEndpoint,
            String... maskedFields) {
        StringBuilder regex = new StringBuilder("^");
        for (String segment : template.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            regex.append('/');
            if (segment.startsWith("{") && segment.endsWith("}")) {
                regex.append("([^/]+)");
            } else {
                regex.append(Pattern.quote(segment));
            }
        }
        regex.append("/?$");
        return new EndpointRule(method.toUpperCase(Locale.ROOT), template, Pattern.compile(regex.toString()),
                requiredCapability, auditAction, resourceType, List.of(maskedFields), publicEndpoint);
    }

    /**
     * 端点策略（一条 HTTP 方法 + 路径模板 → 所需能力 + 审计口径）。
     *
     * @param method             大写 HTTP 方法
     * @param template           路径模板（如 {@code /api/v1/tickets/{ticketNo}/transition}）
     * @param pattern            编译后的全路径正则（动态段为捕获组）
     * @param requiredCapability 所需能力（{@code null} = 只要已登录）
     * @param auditAction        写操作审计动作（{@code null} = 只读，不落写审计）
     * @param resourceType       审计资源类型（403 拒绝也用它）
     * @param maskedFields       审计脱敏字段名（只记字段名，不记原文）
     * @param publicEndpoint     是否公开端点（演示豁免，不鉴权）
     */
    public record EndpointRule(String method, String template, Pattern pattern, String requiredCapability,
            AuditAction auditAction, AuditResourceType resourceType, List<String> maskedFields,
            boolean publicEndpoint) {

        public boolean matches(String path) {
            return pattern.matcher(path).matches();
        }

        /** 取路径里的动态段（如 ticketNo / mappingId / connectorId / roomCode）；无动态段返回 {@code null}。 */
        public String extractResourceId(String path) {
            Matcher matcher = pattern.matcher(path);
            return matcher.matches() && matcher.groupCount() >= 1 ? matcher.group(1) : null;
        }
    }
}
