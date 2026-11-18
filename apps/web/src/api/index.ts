import { healthCheck, metaApi, request, requestBlob, requestMultipart, type ApiResult } from './client'
import { DEFAULT_RECON_SAMPLE_SIZE, KNOWLEDGE_SEARCH_DEFAULT_LIMIT } from './contract'
import type {
  Actor,
  ArrearsListResult,
  ArrearsQuery,
  AuditEvent,
  AuditEventListResult,
  AuditEventQuery,
  BillExplanation,
  BillExplanationQuery,
  BillInfo,
  BillListQuery,
  BillListResult,
  Complaint,
  ComplaintCreateRequest,
  ComplaintListQuery,
  ComplaintListResult,
  ComplaintStats,
  ComplaintStatsQuery,
  ComplaintTransitionRequest,
  ConnectorDetail,
  ConnectorSdk,
  ConnectorSummary,
  CopilotBroadcastRequest,
  CopilotBroadcastResult,
  CopilotDigest,
  CopilotPortrait,
  CopilotReport,
  CopilotScript,
  CopilotScriptRequest,
  CopilotSummary,
  CopilotSummaryRequest,
  CopilotWorkspaceListResult,
  DegradeLedgerQuery,
  DegradeLedgerResult,
  DeskQaSample,
  DeskQaSampleListResult,
  DeskQaSampleStatus,
  DeskQaScoreRequest,
  DeskQaStats,
  DeskQueue,
  DeskReleaseResult,
  DeskSla,
  DeskTakeoverRequest,
  DeskTakeoverResult,
  DunningCloseRequest,
  DunningCloseResult,
  DunningFunnel,
  DunningFunnelQuery,
  DunningScript,
  DunningScriptRequest,
  DunningSendRequest,
  DunningSendResult,
  DunningTask,
  DunningTaskListQuery,
  DunningTaskListResult,
  EventListResult,
  EventType,
  GrayCompareResult,
  GrayPolicy,
  GrayPolicyCreateRequest,
  GrayPolicyUpdateRequest,
  GrayScope,
  HandoffSummary,
  KnowledgeChange,
  KnowledgeChangeRequest,
  KnowledgeCategory,
  KnowledgeCommitResult,
  KnowledgeDetail,
  KnowledgeImportCommitRequest,
  KnowledgeImportCreateRequest,
  KnowledgeImportJob,
  KnowledgeImportJobListResult,
  KnowledgeImportJobStatus,
  KnowledgeItemStatus,
  KnowledgeListResult,
  KnowledgeReviewRequest,
  KnowledgeSearchResult,
  KnowledgeStats,
  KnowledgeVersionListResult,
  LoginRequest,
  LoginResult,
  LogoutResult,
  MappingRollbackRequest,
  MappingTrialReport,
  MappingVersion,
  OwnerVerifyRequest,
  OwnerVerifyResult,
  PageQuery,
  ProbeLatestResult,
  ProbeListResult,
  ProbeReport,
  Project,
  ReconReport,
  ReconRunListResult,
  ReconRunRequest,
  ReconRunResult,
  ReconScope,
  RoomInfo,
  RoomListQuery,
  RoomListResult,
  RagHealth,
  RagQueryRequest,
  RagQueryResult,
  Ticket,
  TicketCreateRequest,
  TicketCreateResult,
  TicketFeedbackRequest,
  TicketFeedbackResult,
  TicketListQuery,
  TicketListResult,
  TicketPushListResult,
  TicketTransitionRequest,
  TicketTransitionResult,
  TraceDetail,
  TraceEvidence,
  TraceSearchResult,
} from './contract'

/**
 * 对账差异 CSV 导出结果（P3-031，契约 v1.1.0 `reconReportExport`）
 * ---------------------------------------------------------------------------
 * 文件流端点 2xx **不是**统一 JSON 信封，而是 `text/csv` 附件；这里把二进制与
 * `Content-Disposition` 文件名收成一个结果对象，页面据此做真实下载（Blob + `a[download]`）与 Toast 回执。
 */
export interface ReconReportExport {
  runId: string
  /** 服务端 `Content-Disposition` 文件名；缺失时回落 `recon-<runId>.csv` */
  filename: string
  contentType: string
  /** 字节数（页面回执用；来自 Blob，不是自算业务数字） */
  size: number
  blob: Blob
}

/**
 * 知识列表查询入参（契约 `GET /api/v1/knowledge` 的 query 形状）。
 * 说明：shared §13 只固化了**响应**形状（`KnowledgeListResult` 等），列表 query 属于请求侧组合参数，
 * 这里按 OpenAPI `knowledgeList` 的 6 个可选参数声明（不新增契约类型、不与响应类型分叉）。
 */
export interface KnowledgeListQuery extends PageQuery {
  /** 状态过滤（省略=全部；owner 恒按 published） */
  status?: KnowledgeItemStatus
  /** 分类过滤（契约 `KnowledgeCategory` 11 类） */
  category?: KnowledgeCategory
  /** 责任人精确匹配（岗位/掩码名，如「财务主管」） */
  owner?: string
  /** 生效期起点：条目 `effectiveFrom >= 该日`（`YYYY-MM-DD`） */
  effectiveFrom?: string
  /** 生效期终点：条目 `effectiveFrom <= 该日`（`YYYY-MM-DD`，契约按起点比较） */
  effectiveTo?: string
}

/** 知识检索入参（契约 `GET /api/v1/knowledge/search`：`q` 必填 ≤200 字符，`limit` 默认 10 上限 50） */
export interface KnowledgeSearchQuery {
  q: string
  limit?: number
}

/* ============================================================================
 * P6 W34（task-87）知识导入入参形状（契约 v1.5.0 §12；请求侧组合参数，不改响应契约）
 * ========================================================================== */

/** 导入任务列表入参（`GET /api/v1/knowledge/imports?status&page&pageSize`；`status` 省略 = 全部） */
export interface KnowledgeImportListQuery extends PageQuery {
  /** 任务状态过滤（shared `KnowledgeImportJobStatus` 全集，顺序固定） */
  status?: KnowledgeImportJobStatus
}

/**
 * multipart 导入的附加表单字段（`POST /api/v1/knowledge/imports` 文件分支）。
 * - `sourceType` 缺省 = `file`（单文件）；ZIP 批量必须显式 `batch`（另需 `knowledge.import.batch`）；
 * - `category` 取 shared `KnowledgeCategory`（11 类），非法值服务端 400 `E-1001`。
 */
export interface KnowledgeImportFileOptions {
  title?: string
  category?: KnowledgeCategory
  sourceType?: 'file' | 'batch'
}

/**
 * 知识导入超时（上传/解析是**同步**链路：Java 调编排层解析 ≤30s，ZIP 逐文件解析更久）。
 * 前端取 120s 覆盖「10MB 单文件 / 50MB ZIP 多文件解析」，避免误报 E-4001；
 * 轮询间隔 2s（`GET /imports/{jobId}` 直到 preview_ready/failed/cancelled）。
 */
export const KNOWLEDGE_IMPORT_UPLOAD_TIMEOUT_MS = 120_000
export const KNOWLEDGE_IMPORT_CREATE_TIMEOUT_MS = 60_000
export const KNOWLEDGE_IMPORT_COMMIT_TIMEOUT_MS = 60_000
export const KNOWLEDGE_IMPORT_POLL_INTERVAL_MS = 2_000
/** 轮询最大次数（2s × 60 = 120s；超时后页面给「超时」态，可继续手动刷新） */
export const KNOWLEDGE_IMPORT_POLL_MAX_ATTEMPTS = 60
/** 导入任务列表每页条数（服务端 pageSize 上限 100） */
export const KNOWLEDGE_IMPORT_JOB_PAGE_SIZE = 10

/**
 * 证据包 CSV 导出结果（P4-020，契约 v1.2.0 `traceEvidence` 的文件流例外）
 * ---------------------------------------------------------------------------
 * `format=csv` 的 2xx 是 `text/csv` 附件（**非 JSON 信封**）；文件名取 `Content-Disposition`
 * （服务端形如 `trace-<traceId>.csv`），页面据此做真实下载（Blob + `a[download]`）。
 * `format=json` 走统一 JSON 信封（`api.trace.evidenceJson`），页面把信封里的 `data` 序列化成
 * `trace-<traceId>-evidence.json` 下载 —— 两条路径都不伪造文件名/内容。
 */
export interface TraceEvidenceExport {
  traceId: string
  /** 服务端 `Content-Disposition` 文件名；缺失时回落 `trace-<traceId>.csv` */
  filename: string
  contentType: string
  /** 字节数（回执用；来自 Blob） */
  size: number
  blob: Blob
}

/**
 * 回溯检索入参（契约 `GET /api/v1/trace/search` 的 query 形状：筛选条件全部可选，
 * 按 `lastAt` 降序；分页 `page`/`pageSize` 同 README §2）。
 */
export interface TraceSearchQuery extends PageQuery {
  /** 操作主体（账号/工号，掩码） */
  actorId?: string
  /** 动作（与审计 action 同口径，如 `ticket.create`） */
  action?: string
  /** 起始时间（ISO 8601；省略=不限） */
  from?: string
  /** 结束时间（ISO 8601；省略=不限） */
  to?: string
}

/** 质检抽样列表入参（契约 `GET /api/v1/desk/qa/samples`：`status` 过滤 + 分页） */
export interface DeskQaSamplesQuery extends PageQuery {
  status?: DeskQaSampleStatus
}

/* ============================================================================
 * P5 W19（契约 v1.3.0 §14）入参形状
 * ========================================================================== */

/**
 * 映射版本列表入参（v1.3.0）：`connectorId` 按连接器筛选（省略 = 全部连接器）。
 * 跨连接器操作（如发布时 body.connectorId 与映射自身 connectorId 不一致）→ 403 `E-2002` `cross_connector`。
 */
export interface MappingListQuery {
  connectorId?: string
}

/** 灰度双跑比对入参（`from`/`to` **必填**；`connectorId` 省略 = 全部处于灰度中的连接器） */
export interface GrayCompareQuery {
  from: string
  to: string
  connectorId?: string
}

/**
 * 事件查询入参（`GET /api/v1/events`）：`type` 事件类型、`projectId` 项目
 * （缺省 = 当前 actor 项目；跨项目 → 403 `E-2002` `cross_project`）、`from`/`to` 按 `occurredAt` 闭区间。
 */
export interface EventListQuery extends PageQuery {
  type?: EventType
  projectId?: string
  from?: string
  to?: string
}

/** 灰度策略作用域筛选（前端本地筛选用；服务端全量小集合返回） */
export type GrayScopeFilter = GrayScope | ''

/**
 * RAG 问答超时（`POST /api/v1/rag/query`，P6 W33）
 * ---------------------------------------------------------------------------
 * 链路：Java BFF → `services/ai-orchestrator`（检索 + 可选生成）→ 规则兜底；
 * BFF 对编排层的超时是 8s（超时即降级为 `rules` 规则检索并如实回 `degraded`），
 * 因此客户端取 25s：足够覆盖「编排层超时 + 规则检索兜底」的最坏路径，
 * 只有真正挂死时才落到页面「超时」态（`E-4001`，可重试）。
 */
export const RAG_QUERY_TIMEOUT_MS = 25_000

/** RAG 健康只读端点超时（`GET /api/v1/rag/health`，编排层探测 ≤8s） */
export const RAG_HEALTH_TIMEOUT_MS = 12_000

/**
 * P2 前端 API SDK（契约 v0.3.3；P3 W10 增量对齐 v1.1.0，唯一事实源 `docs/openapi/openapi.yaml`）
 * ---------------------------------------------------------------------------
 * | 前端方法 | 契约端点 | 使用页面 |
 * |---|---|---|
 * | `api.auth.login` | POST /api/v1/auth/login（公开） | 登录页 `/login`、顶栏演示账号切换、`?role=` 深链 |
 * | `api.auth.me` | GET /api/v1/auth/me | 登录态回读（本轮未主动轮询；token 失效由 401 出口兜底） |
 * | `api.auth.logout` | POST /api/v1/auth/logout | 顶栏「退出演示账号」 |
 * | `api.audit.events` | GET /api/v1/audit/events?actorId&action&resourceType&resourceId&from&to&page&pageSize | ERP 适配中心 · 审计页签（it/admin/supervisor） |
 * | `api.audit.get` | GET /api/v1/audit/events/{eventId} | 审计事件详情（本轮列表已够用，保留 SDK） |
 * | `api.owner.verify` | POST /api/v1/owner/verify | 业主端（ButlerChat 核验 chips） |
 * | `api.room.get` | GET /api/v1/rooms/{roomCode} | 业主端会话（带出房档）、坐席上下文 |
 * | `api.room.list` | GET /api/v1/rooms?page&pageSize&building&bindState&sort | v0.3.2 房档列表（带 `freshness`） |
 * | `api.bill.query` | GET /api/v1/bills?roomCode=&period= | 业主端账单卡（金额全部由 ERP 给出） |
 * | `api.bill.list` | GET /api/v1/bills?period&status&building&sort&page&pageSize | v0.3.2 账单列表（省略 roomCode 的列表模式） |
 * | `api.fee.arrears` | GET /api/v1/fees/arrears?building&bucket&page&pageSize | P3 W10 /billing 欠费分层视图（D1/D7/D15/D30 + `summary.buckets`） |
 * | `api.fee.explain` | GET /api/v1/fees/explain?roomCode&period&itemCode | P3 W10 /billing 费用解释卡、业主端账单解释（formula/breakdown/diffVsPrev/knowledgeRefs） |
 * | `api.dunning.tasks` | GET /api/v1/dunning/tasks?status&bucket&assignee&page&pageSize | P3 W11 /billing 催缴工作台任务列表（含 `frequencyCheck`） |
 * | `api.dunning.task` | GET /api/v1/dunning/tasks/{taskId} | 催缴工作台详情（频次红线实时校验） |
 * | `api.dunning.script` | POST /api/v1/dunning/tasks/{taskId}/script | 话术生成（tone/channel → content + `forbiddenHits` + version；生成≠外发） |
 * | `api.dunning.send` | POST /api/v1/dunning/tasks/{taskId}/send（Idempotency-Key 可选） | 人工确认触达（`confirmed:true`；频次红线 409 E-3003；`status:mocked`） |
 * | `api.dunning.close` | POST /api/v1/dunning/tasks/{taskId}/close | 关闭任务（reason 必填，只改任务状态、不写财务） |
 * | `api.dunning.funnel` | GET /api/v1/dunning/funnel?from&to | P3 W11 费用看板催缴漏斗（reached/responded/promised/paid + 4 转化率） |
 * | `api.complaint.create` | POST /api/v1/complaints（201；Idempotency-Key 可选） | P3 W12 业主端「我的投诉」提交（owner 本户，跨房 403 E-2002） |
 * | `api.complaint.list` | GET /api/v1/complaints?status&level&roomCode&sort&page&pageSize | 投诉处理台列表（服务端分页/排序；owner 仅本户） |
 * | `api.complaint.get` | GET /api/v1/complaints/{complaintId} | 投诉详情（audit[] 时间线 / SLA / 关联工单；owner 他人 → 403 E-2002） |
 * | `api.complaint.transition` | POST /api/v1/complaints/{complaintId}/transition | accept / first_response / escalate / close / reopen（version 乐观锁） |
 * | `api.complaint.stats` | GET /api/v1/complaints/stats?from&to&level | 投诉统计卡（total/byStatus/byLevel/avgFirstResponseMinutes/slaBreachCount） |
 * | `api.knowledge.list` | GET /api/v1/knowledge?status&category&owner&effectiveFrom&effectiveTo&page&pageSize | P4 W15 知识运营中心列表（服务端筛选/分页；owner 恒 published+生效期） |
 * | `api.knowledge.get` | GET /api/v1/knowledge/{knowledgeId} | 知识详情（含 `versions[]` 版本历史；owner 读未生效/过期 → 404） |
 * | `api.knowledge.versions` | GET /api/v1/knowledge/{knowledgeId}/versions | 版本历史（按提交时间升序，不分页） |
 * | `api.knowledge.change` | POST /api/v1/knowledge/{knowledgeId}/change（201） | 提交知识变更 → `pending_review`（不直接生效；同知识已有待审 → 409） |
 * | `api.knowledge.review` | POST /api/v1/knowledge/{knowledgeId}/review | 变更审核 approved/rejected（**仅 it/admin**；驳回 note 必填；终态/不属于 → 409） |
 * | `api.knowledge.search` | GET /api/v1/knowledge/search?q&limit | Copilot 知识秒查（只回 published+生效期，带 `cite`；无命中 items: []） |
 * | `api.knowledge.stats` | GET /api/v1/knowledge/stats?from&to | 采纳看板（adoptionRate/gaps/expiredSoon/owners，服务端聚合，前端不自算） |
 * | `api.ticket.create` | POST /api/v1/tickets（Idempotency-Key 必填） | 业主端 5 问报修建单 |
 * | `api.ticket.get` | GET /api/v1/tickets/{ticketNo} | 进度页 7 节点时间轴（深链 ?ticket=） |
 * | `api.ticket.list` | GET /api/v1/tickets?status=&team=&category=&dateFrom=&dateTo=&sort=&page=&pageSize= | 坐席工单列表（筛选/排序/分页）、Copilot 摘要选单 |
 * | `api.ticket.pushes` | GET /api/v1/tickets/{ticketNo}/pushes | 进度页三次推送（v0.3.1，mock 通道留痕） |
 * | `api.ticket.transition` | POST /api/v1/tickets/{ticketNo}/transition | 进度页派单/改约/催办（写操作幂等；dispatch/complete 带 erpWriteback） |
 * | `api.ticket.feedback` | POST /api/v1/tickets/{ticketNo}/feedback | 进度页评价服务（写操作幂等；结果含 feedback 摘要） |
 * | `api.ticket.handoffSummary` | GET /api/v1/tickets/{ticketNo}/handoff-summary | 坐席同屏摘要、Copilot 工单摘要 |
 * | `api.connector.list` | GET /api/v1/connectors | ERP 适配中心 · 连接器卡片（5 套注册表摘要） |
 * | `api.connector.get` | GET /api/v1/connectors/{id} | ERP 适配中心 · 18 项能力三态矩阵 |
 * | `api.connector.probe` | POST /api/v1/connectors/{id}/probe | ERP 适配中心 · 能力探测（日报 + drift） |
 * | `api.connector.probeLatest` | GET /api/v1/connectors/{id}/probe/latest | ERP 适配中心 · 最近一次 + 近 7 日历史 |
 * | `api.connector.probes` | GET /api/v1/connectors/{id}/probes?page&pageSize | ERP 适配中心 · 探测日报分页历史 |
 * | `api.mapping.list` | GET /api/v1/mappings | ERP 适配中心 · 映射版本列表（`data` 直接是数组） |
 * | `api.mapping.get` | GET /api/v1/mappings/{id} | ERP 适配中心 · 映射字段明细 |
 * | `api.mapping.trial` | POST /api/v1/mappings/{id}/trial | ERP 适配中心 · 200 样本试跑报告 |
 * | `api.mapping.publish` | POST /api/v1/mappings/{id}/publish | ERP 适配中心 · 发布（仅 trial_passed） |
 * | `api.mapping.rollback` | POST /api/v1/mappings/{id}/rollback | ERP 适配中心 · 回滚到上一个已发布版本 |
 * | `api.degrade.ledger` | GET /api/v1/degrade/ledger?connectorId&from&to&page&pageSize | ERP 适配中心 · 降级台账 |
 * | `api.recon.runs` | GET /api/v1/recon/runs?page&pageSize | ERP 适配中心 · 对账历史 |
 * | `api.recon.run` | POST /api/v1/recon/run（202） | P2-013 只读对账（门禁页 / 适配中心对账页签） |
 * | `api.recon.report` | GET /api/v1/recon/report/{runId} | 对账报告轮询 |
 * | `api.recon.exportReport` | GET /api/v1/recon/report/{runId}/export?format=csv | 对账面板「导出差异 CSV」（文件流例外；403/404/409 可解释） |
 *
 * P5 W19 增量（契约 v1.3.0 §14，多项目 / 多连接器 / 灰度 / 事件）：
 * | 前端方法 | 契约端点 | 使用页面 |
 * |---|---|---|
 * | `api.project.list` | GET /api/v1/projects | 顶栏/侧栏项目切换器（it/admin 3 个项目；owner/staff 仅本项目） |
 * | `api.project.get` | GET /api/v1/projects/{projectId} | 项目详情（房档数/主连接器；跨项目 403 E-2002 cross_project） |
 * | `api.connector.sdk` | GET /api/v1/connectors/{id}/sdk | ERP 适配中心 · SDK 元数据卡（sdkVersion/capabilities/fieldCount/limits） |
 * | `api.mapping.list({connectorId})` | GET /api/v1/mappings?connectorId= | 映射中心连接器筛选（跨连接器隔离） |
 * | `api.gray.policies` | GET /api/v1/gray/policies | 灰度与双跑页签 · 策略列表（it/admin 管理；staff 只读） |
 * | `api.gray.createPolicy` | POST /api/v1/gray/policies（201，Idempotency-Key 可选） | 新建灰度策略（scope+targetId 唯一，重复 409 E-3002） |
 * | `api.gray.updatePolicy` | PATCH /api/v1/gray/policies/{policyId} | 调整比例 / 启停（enabled=false 即回滚，≤5 分钟） |
 * | `api.gray.compare` | GET /api/v1/gray/compare?from&to&connectorId | 双跑比对表（逐能力 checked/matched/diffRate） |
 * | `api.event.list` | GET /api/v1/events?type&projectId&from&to&page&pageSize | 事件页 `/events`（supervisor/it/admin；outbox 只读） |
 *
 * P6 W33 增量（契约 v1.4.0 §11，RAG；task-81）：
 * | 前端方法 | 契约端点 | 使用页面 |
 * |---|---|---|
 * | `api.rag.query` | POST /api/v1/rag/query（`rag.query`，全角色） | 知识页「智能问答」页签、Copilot 知识秒查（answer + `[n]` 引用 + 拒答 + 模式徽标） |
 * | `api.rag.health` | GET /api/v1/rag/health（`rag.health`，**仅 it/admin**） | 知识页「RAG 健康」页签（组件状态表 + 降级说明） |
 *
 * RAG 口径（契约 v1.4.0 `RagQueryResult` / `RagHealth`，AGENTS.md NEVER #11）：
 * - **拒答不是错误**：`answerable=false` 仍 200 → 页面给人话原因 + 转人工入口，绝不编造答案；
 * - `mode` / `reranked` 必须如实展示（`generative|retrieval_only|rules`）：不得把仅检索/规则兜底说成生成答案；
 * - `[n]` 引用编号必须 ⊆ `sources[].index`（服务端校验；前端只做高亮/跳转，不改写编号）；
 * - `degraded` 出现时页面标注「已降级」并展示服务端原因；`traceId` 用于回溯，页面只展示不落日志。
 *
 * P6 W34 增量（契约 v1.5.0 §12，知识导入；task-87）：
 * | 前端方法 | 契约端点 | 使用页面 |
 * |---|---|---|
 * | `api.knowledgeImport.createPaste` | POST /api/v1/knowledge/imports（JSON：title+content） | 知识页「导入」页签 · 粘贴文本 / RAG 缺口沉淀（sourceType=badcase） |
 * | `api.knowledgeImport.createFile` | POST /api/v1/knowledge/imports（multipart：file + title/category/sourceType） | 单文件（txt/md/docx/pdf ≤10MB）/ ZIP 批量（sourceType=batch，≤50 文件/50MB） |
 * | `api.knowledgeImport.list` | GET /api/v1/knowledge/imports?status&page&pageSize | 导入任务列表（服务端分页，仅本项目） |
 * | `api.knowledgeImport.get` | GET /api/v1/knowledge/imports/{jobId} | 预览页 / 轮询（2s，直到 preview_ready/failed） |
 * | `api.knowledgeImport.commit` | POST /api/v1/knowledge/imports/{jobId}/commit | 预览提交（默认 pending_review；approved 仅 it/admin 直审并即时索引） |
 * | `api.knowledgeImport.cancel` | POST /api/v1/knowledge/imports/{jobId}/cancel | 取消任务（终态 409 E-3002） |
 *
 * 导入口径（契约 v1.5.0 `KnowledgeImportJob` / `KnowledgeCommitResult`）：
 * - 权限：`knowledge.import`（steward/agent/supervisor/it/admin；owner 403）；ZIP 批量另需
 *   `knowledge.import.batch`；`approved` 直审仅 it/admin（前端隐藏入口，服务端 403 `E-2001` 兜底）；
 * - 限额：单文件 ≤10MB / 粘贴 ≤200,000 字符 / ZIP ≤50 文件·50MB / 10 次·分·账号
 *   （超限 413 `E-1001`、超频 429 `E-4003`）；前端提示数字取自 shared `KNOWLEDGE_IMPORT_LIMITS`；
 * - 预览是**同步**返回的（201 即带 chunks；解析失败也是 201 + status=failed + error），
 *   页面仍按 2s 轮询到终态，避免把 in-flight 任务当完成；
 * - multipart 上传不手工设 `Content-Type`（boundary 由浏览器补；`requestMultipart`）。
 *
 * 约定：
 * - 写操作（create/transition/feedback）**必须**传 `idempotencyKey`（统一由
 *   `@/composables/useIdempotencyKey` 生成 `ui-` 前缀 UUID，同一动作重试复用同键）；
 * - v0.3.2 配置类动作（probe/trial/publish/rollback/recon.run）`Idempotency-Key` 可选：
 *   页面按「动作 + 关键参数」复用同键，重试不重复执行；
 * - `GET /connectors`、`GET /mappings` 的 `data` **直接是数组**（全量小集合，不分页）；
 * - 返回值带 `degraded` 时页面必须标注「已降级」；`mock: true` 时标注「演示数据（mock ERP）」；
 * - 失败抛 `ApiError`（17 类错误码），人话文案走 `@/api/error-copy`。
 */
export const api = {
  health: () => healthCheck(),
  listScreens: metaApi.listScreens,
  getScreen: metaApi.getScreen,

  /**
   * P5 W19 多项目隔离（契约 v1.3.0 §14，架构 §24.7）
   * - `list`：返回当前主体**可见**的项目（`data` 直接是数组，全量小集合 ≤20）：
   *   owner/steward/agent/supervisor 仅本项目（`Actor.projectId`），it/admin 平台级可见全部 3 个项目；
   * - `get`：项目详情（房档数/主连接器），非本项目主体请求他项目 → 403 `E-2002`
   *   `details.reason=cross_project`；项目不存在 → 404 `E-3001`；
   * - 演示口径：3 个项目为**演示数据**（shared `DEMO_PROJECTS`），不接真实组织架构。
   */
  project: {
    list: (): Promise<ApiResult<Project[]>> => request<Project[]>('/api/v1/projects'),

    get: (projectId: string): Promise<ApiResult<Project>> =>
      request<Project>(`/api/v1/projects/${encodeURIComponent(projectId)}`),
  },

  /**
   * P2 W8 演示登录态（契约 v0.3.3 §11.9.1 / P2-029）
   * - `login`：公开端点（`security: []`），演示账号 + 固定口令 → 演示 JWT；
   *   401 是「账号或口令错误」，**不跳登录页**（`noAuthRedirect`），由登录页就地提示；
   * - `me`：校验当前 Bearer 并回读 `Actor`（token 失效 → 全局 401 出口跳 /login）；
   * - `logout`：撤销当前 token + 落 `auth.logout` 审计（幂等 200），失败也不阻塞前端清态。
   */
  auth: {
    login: (body: LoginRequest): Promise<ApiResult<LoginResult>> =>
      request<LoginResult>('/api/v1/auth/login', { method: 'POST', body, noAuthRedirect: true }),

    me: (): Promise<ApiResult<Actor>> => request<Actor>('/api/v1/auth/me'),

    logout: (): Promise<ApiResult<LogoutResult>> =>
      request<LogoutResult>('/api/v1/auth/logout', { method: 'POST', body: {}, noAuthRedirect: true }),
  },

  /**
   * P2 W8 审计（契约 v0.3.3 §11.9.2 / P2-031）：仅 `it`/`admin`/`supervisor` 可读；
   * 其它角色 → 403 `details.requiredCapability = audit.read`（页面渲染「无权限」态）。
   * P2 为进程内演示存储（重启清空），只回脱敏信息。
   */
  audit: {
    events: (query: AuditEventQuery = {}): Promise<ApiResult<AuditEventListResult>> => {
      const params = pageParams(query)
      if (query.actorId) params.set('actorId', query.actorId)
      if (query.action) params.set('action', query.action)
      if (query.resourceType) params.set('resourceType', query.resourceType)
      if (query.resourceId) params.set('resourceId', query.resourceId)
      if (query.from) params.set('from', query.from)
      if (query.to) params.set('to', query.to)
      const suffix = params.toString() ? `?${params.toString()}` : ''
      return request<AuditEventListResult>(`/api/v1/audit/events${suffix}`)
    },

    get: (eventId: string): Promise<ApiResult<AuditEvent>> =>
      request<AuditEvent>(`/api/v1/audit/events/${encodeURIComponent(eventId)}`),
  },

  /** CAP-02 业主身份核验（手机号后四位；失败不提示错因，连续 3 次转人工） */
  owner: {
    verify: (body: OwnerVerifyRequest): Promise<ApiResult<OwnerVerifyResult>> =>
      request<OwnerVerifyResult>('/api/v1/owner/verify', { method: 'POST', body }),
  },

  /** CAP-01 房档只读查询（只读，不落库） */
  room: {
    get: (roomCode: string): Promise<ApiResult<RoomInfo>> =>
      request<RoomInfo>(`/api/v1/rooms/${encodeURIComponent(roomCode)}`),

    /**
     * 房档列表（v0.3.2 `GET /api/v1/rooms`，单条端点保持兼容）。
     * 响应 `items[].freshness` 为数据新鲜度（缓存/降级时必须展示来源与数据时间）。
     */
    list: (query: RoomListQuery = {}): Promise<ApiResult<RoomListResult>> => {
      const params = pageParams(query)
      if (query.building) params.set('building', query.building)
      if (query.bindState) params.set('bindState', query.bindState)
      if (query.sort) params.set('sort', query.sort)
      return request<RoomListResult>(`/api/v1/rooms?${params.toString()}`)
    },
  },

  /** CAP-03 账单只读查询（金额/差额/引用全部由 ERP 给出，AI 不自算） */
  bill: {
    /** 单账单模式：必须带 `roomCode` + `period`（W5 形状不变） */
    query: (params: { roomCode: string; period: string }): Promise<ApiResult<BillInfo>> => {
      const query = new URLSearchParams({ roomCode: params.roomCode, period: params.period })
      return request<BillInfo>(`/api/v1/bills?${query.toString()}`)
    },

    /** 列表模式（v0.3.2）：省略 `roomCode`，返回分页 `{items,page,pageSize,total}` */
    list: (query: BillListQuery = {}): Promise<ApiResult<BillListResult>> => {
      const params = pageParams(query)
      if (query.period) params.set('period', query.period)
      if (query.status) params.set('status', query.status)
      if (query.building) params.set('building', query.building)
      if (query.sort) params.set('sort', query.sort)
      return request<BillListResult>(`/api/v1/bills?${params.toString()}`)
    },
  },

  /**
   * P3 W10 费用只读（契约 v1.1.0 §12.1 / P3-007/008）
   * -------------------------------------------------------------------------
   * | 前端方法 | 契约端点 | 使用页面 |
   * |---|---|---|
   * | `api.fee.arrears` | GET /api/v1/fees/arrears?building&bucket&page&pageSize | /billing 欠费分层视图（steward/supervisor；owner 仅本户） |
   * | `api.fee.explain` | GET /api/v1/fees/explain?roomCode&period&itemCode | /billing 费用解释卡、业主端账单解释 |
   *
   * 金额口径：`total/outstanding/lateFee/amount` 全部以 ERP 返回为准，前端只格式化、**不自算**；
   * 解释必须带 `knowledgeRefs`（无出处服务端拒答），`mock: true` 时页面标注「演示数据（mock ERP）」。
   */
  fee: {
    /** 欠费台账（逾期分层 D1/D7/D15/D30 + 分层统计 `summary.buckets`，服务端分页） */
    arrears: (query: ArrearsQuery = {}): Promise<ApiResult<ArrearsListResult>> => {
      const params = pageParams(query)
      if (query.building) params.set('building', query.building)
      if (query.bucket) params.set('bucket', query.bucket)
      return request<ArrearsListResult>(`/api/v1/fees/arrears?${params.toString()}`)
    },

    /**
     * 账单解释：公式 + 分摊明细 + 与上期差异 + 知识引用。
     * owner 传他人房号 → 403 `E-2002`（`details.reason=out_of_scope`），页面按人话提示。
     */
    explain: (query: BillExplanationQuery): Promise<ApiResult<BillExplanation>> => {
      const params = new URLSearchParams({ roomCode: query.roomCode, period: query.period })
      if (query.itemCode) params.set('itemCode', query.itemCode)
      return request<BillExplanation>(`/api/v1/fees/explain?${params.toString()}`)
    },
  },

  /**
   * P3 W11 催缴（契约 v1.1.0 §12.2 / P3-016~018）
   * -------------------------------------------------------------------------
   * | 前端方法 | 契约端点 | 使用页面 |
   * |---|---|---|
   * | `api.dunning.tasks` | GET /api/v1/dunning/tasks | /billing「催缴工作台」列表（status/bucket/assignee 筛选 + 分页） |
   * | `api.dunning.task` | GET /api/v1/dunning/tasks/{taskId} | 工作台详情（`frequencyCheck` 实时红线） |
   * | `api.dunning.script` | POST .../script | 话术生成（生成不等于外发；`forbiddenHits` 非空不得发送） |
   * | `api.dunning.send` | POST .../send | 人工确认触达（`confirmed:true`；mock-channel，`status:mocked`） |
   * | `api.dunning.close` | POST .../close | 关闭任务（reason 必填；只改任务状态，不写财务） |
   * | `api.dunning.funnel` | GET /api/v1/dunning/funnel | 费用看板催缴漏斗（转化率来自服务端，前端只格式化） |
   *
   * 红线（AGENTS.md NEVER #4/#7）：触达只能单户、人工确认后经 `services/mock-channel` 受理，
   * **不接真实企微/短信/语音外呼**；`status=mocked` 不得说成「已送达业主」。
   * 频次红线：`frequencyCheck.allowed=false` → 页面禁用发送；服务端仍强制 409 `E-3003`（前端隐藏不是安全边界）。
   */
  dunning: {
    /** 任务列表（`status`/`bucket`/`assignee` 全部可选；分页 1—100） */
    tasks: (query: DunningTaskListQuery = {}): Promise<ApiResult<DunningTaskListResult>> => {
      const params = pageParams(query)
      if (query.status) params.set('status', query.status)
      if (query.bucket) params.set('bucket', query.bucket)
      if (query.assignee) params.set('assignee', query.assignee)
      return request<DunningTaskListResult>(`/api/v1/dunning/tasks?${params.toString()}`)
    },

    /** 任务详情（不存在 → 404 `E-3001`；越权 → 403 `E-2001`） */
    task: (taskId: string): Promise<ApiResult<DunningTask>> =>
      request<DunningTask>(`/api/v1/dunning/tasks/${encodeURIComponent(taskId)}`),

    /** 生成话术（写操作：落话术版本 + 审计；不触达业主） */
    script: (taskId: string, body: DunningScriptRequest = {}): Promise<ApiResult<DunningScript>> =>
      request<DunningScript>(`/api/v1/dunning/tasks/${encodeURIComponent(taskId)}/script`, {
        method: 'POST',
        body,
      }),

    /**
     * 发送催缴（人工确认后单户触达 mock 通道）。
     * `Idempotency-Key` 可选：同一动作重试复用同键，避免重复留痕。
     */
    send: (
      taskId: string,
      body: DunningSendRequest,
      idempotencyKey?: string,
    ): Promise<ApiResult<DunningSendResult>> =>
      request<DunningSendResult>(`/api/v1/dunning/tasks/${encodeURIComponent(taskId)}/send`, {
        method: 'POST',
        body,
        idempotencyKey,
      }),

    /** 关闭任务（只改任务状态，不写财务数据；重复关闭 → 409 `E-3002`） */
    close: (taskId: string, body: DunningCloseRequest): Promise<ApiResult<DunningCloseResult>> =>
      request<DunningCloseResult>(`/api/v1/dunning/tasks/${encodeURIComponent(taskId)}/close`, {
        method: 'POST',
        body,
      }),

    /** 催缴漏斗（`from`/`to` 省略 = 最近 7 天；`from > to` → 400 `E-1001`） */
    funnel: (query: DunningFunnelQuery = {}): Promise<ApiResult<DunningFunnel>> => {
      const params = new URLSearchParams()
      if (query.from) params.set('from', query.from)
      if (query.to) params.set('to', query.to)
      const suffix = params.toString() ? `?${params.toString()}` : ''
      return request<DunningFunnel>(`/api/v1/dunning/funnel${suffix}`)
    },
  },

  /**
   * P3 W12 投诉受理 / 分级 SLA / 流转 / 统计（契约 v1.1.0 §12.3 / P3-021~023）
   * -------------------------------------------------------------------------
   * | 前端方法 | 契约端点 | 使用页面 |
   * |---|---|---|
   * | `api.complaint.create` | POST /api/v1/complaints | 业主端「我的投诉」提交（201 + slaDueAt） |
   * | `api.complaint.list` | GET /api/v1/complaints | 投诉处理台列表（status/level/roomCode/sort + 分页） |
   * | `api.complaint.get` | GET /api/v1/complaints/{id} | 处理台详情 / 业主端进度（owner 本户） |
   * | `api.complaint.transition` | POST /api/v1/complaints/{id}/transition | 受理/首响/升级/结案/重开（version 乐观锁） |
   * | `api.complaint.stats` | GET /api/v1/complaints/stats | 处理台统计卡（仅 complaint.stats） |
   *
   * 数据范围与红线（AGENTS.md / README §7.2）：
   * - owner 只能看/提交/重开**本户**投诉（跨房 403 `E-2002` + `details.reason=out_of_scope`），
   *   其它动作 403 `E-2001` + `details.requiredCapability`；steward/agent/supervisor 按项目范围；
   * - 投诉处理**必须人工**：本 SDK 只做状态流转与留痕，不做自动回复/自动结案；
   * - 隐私：`content`/`ownerNameMasked` 由服务端脱敏，`contactTail4` 只传手机号后四位、不回传；
   * - 契约 `ComplaintTransitionRequest` 只有 `action/note/version`（**无 ticketNo / target 入参**）：
   *   升级目标与工单联动由服务端按只读规则决定（`escalatedTo` / `ticketNo` 回带），前端不伪造。
   */
  complaint: {
    /** 提交投诉（201；owner 本户；`slaDueAt` 由服务端按级别计算） */
    create: (
      body: ComplaintCreateRequest,
      idempotencyKey?: string,
    ): Promise<ApiResult<Complaint>> =>
      request<Complaint>('/api/v1/complaints', { method: 'POST', body, idempotencyKey }),

    /** 投诉列表（`status`/`level`/`roomCode`/`sort` 全部可选；分页 1—100） */
    list: (query: ComplaintListQuery = {}): Promise<ApiResult<ComplaintListResult>> => {
      const params = pageParams(query)
      if (query.status) params.set('status', query.status)
      if (query.level) params.set('level', query.level)
      if (query.roomCode) params.set('roomCode', query.roomCode)
      if (query.sort) params.set('sort', query.sort)
      return request<ComplaintListResult>(`/api/v1/complaints?${params.toString()}`)
    },

    /** 投诉详情（不存在 404 `E-3001`；owner 他人 403 `E-2002`） */
    get: (complaintId: string): Promise<ApiResult<Complaint>> =>
      request<Complaint>(`/api/v1/complaints/${encodeURIComponent(complaintId)}`),

    /**
     * 投诉流转（写操作；`version` 必填乐观锁）：
     * 非法迁移 409 `E-3002` + `details.allowed`；版本冲突 409 `E-3002` + `details.reason=version_conflict`。
     */
    transition: (
      complaintId: string,
      body: ComplaintTransitionRequest,
      idempotencyKey?: string,
    ): Promise<ApiResult<Complaint>> =>
      request<Complaint>(`/api/v1/complaints/${encodeURIComponent(complaintId)}/transition`, {
        method: 'POST',
        body,
        idempotencyKey,
      }),

    /** 投诉统计（仅 `complaint.stats`；自算口径禁止，数字全部来自服务端） */
    stats: (query: ComplaintStatsQuery = {}): Promise<ApiResult<ComplaintStats>> => {
      const params = new URLSearchParams()
      if (query.from) params.set('from', query.from)
      if (query.to) params.set('to', query.to)
      if (query.level) params.set('level', query.level)
      const suffix = params.toString() ? `?${params.toString()}` : ''
      return request<ComplaintStats>(`/api/v1/complaints/stats${suffix}`)
    },
  },

  /**
   * P4 W15 知识运营中心 + Copilot 知识秒查（契约 v1.2.0 §13 / P4-007/008）
   * -------------------------------------------------------------------------
   * | 前端方法 | 契约端点 | 使用页面 |
   * |---|---|---|
   * | `api.knowledge.list` | GET /api/v1/knowledge | /knowledge 知识运营中心列表（status/category/owner/生效期 + 分页） |
   * | `api.knowledge.get` | GET /api/v1/knowledge/{id} | 详情面板（正文 + 出处 + `versions[]`） |
   * | `api.knowledge.versions` | GET /api/v1/knowledge/{id}/versions | 版本历史（`versions` 与详情同源，保留独立入口） |
   * | `api.knowledge.change` | POST .../change（201） | 变更提交（staff → pending_review，**不直接生效**） |
   * | `api.knowledge.review` | POST .../review | 审核（**仅 it/admin**；approved 生效 / rejected 需 note） |
   * | `api.knowledge.search` | GET /api/v1/knowledge/search | Copilot 知识秒查（命中带 `cite`；无命中 `items: []` → 必须拒答） |
   * | `api.knowledge.stats` | GET /api/v1/knowledge/stats | 采纳看板（adoptionRate / gaps TopN / expiredSoon / owners） |
   *
   * 口径（AGENTS.md「不编造知识」+ README §7.4）：
   * - `search` **只返回 published 且在生效期内**的条目（过期/草稿/待审/驳回不返回），前端不得补展示；
   * - 无命中是 200 + `items: []`（不是错误）：页面必须拒答并转人工/建议提交知识；
   * - `cite` 由服务端拼好（`K-018 V1.2 · 装修管理规定 §3.1`），前端**不自行拼接**；
   * - `stats` 数字（adoptionRate/adopted/rejected/gaps/expiredSoon/owners）全部来自服务端，前端只格式化；
   * - owner 只读 `published` + 生效期（服务端强制），读到未生效/过期 → 404 `E-3001`。
   */
  knowledge: {
    /** 知识列表（服务端筛选 + 分页；`status`/`category` 非法 → 400 `E-1001`） */
    list: (query: KnowledgeListQuery = {}): Promise<ApiResult<KnowledgeListResult>> => {
      const params = pageParams(query)
      if (query.status) params.set('status', query.status)
      if (query.category) params.set('category', query.category)
      if (query.owner) params.set('owner', query.owner)
      if (query.effectiveFrom) params.set('effectiveFrom', query.effectiveFrom)
      if (query.effectiveTo) params.set('effectiveTo', query.effectiveTo)
      const suffix = params.toString() ? `?${params.toString()}` : ''
      return request<KnowledgeListResult>(`/api/v1/knowledge${suffix}`)
    },

    /** 知识详情（不存在/owner 不可见 → 404 `E-3001`，不区分「不存在」与「不可见」） */
    get: (knowledgeId: string): Promise<ApiResult<KnowledgeDetail>> =>
      request<KnowledgeDetail>(`/api/v1/knowledge/${encodeURIComponent(knowledgeId)}`),

    /** 版本历史（按 `submittedAt` 升序；引用必须带版本） */
    versions: (knowledgeId: string): Promise<ApiResult<KnowledgeVersionListResult>> =>
      request<KnowledgeVersionListResult>(
        `/api/v1/knowledge/${encodeURIComponent(knowledgeId)}/versions`,
      ),

    /**
     * 提交变更（写操作，201 → `pending_review`）：
     * 同知识已有待审变更单 → 409 `E-3002`（`details.reason=change_pending`）；
     * `effectiveFrom > effectiveTo` / 必填缺失 → 400 `E-1001`；知识不存在 → 404 `E-3001`。
     */
    change: (knowledgeId: string, body: KnowledgeChangeRequest): Promise<ApiResult<KnowledgeChange>> =>
      request<KnowledgeChange>(`/api/v1/knowledge/${encodeURIComponent(knowledgeId)}/change`, {
        method: 'POST',
        body,
      }),

    /**
     * 审核变更（写操作）：`approved` → 生成新版本并生效；`rejected` → 需 `note`。
     * 仅 it/admin（staff/owner 403 `E-2001`）；提交人自审 → 403（`details.reason=self_review_forbidden`）；
     * 终态/变更单不属于该知识 → 409 `E-3002`（`details.reason=invalid_transition`）。
     */
    review: (knowledgeId: string, body: KnowledgeReviewRequest): Promise<ApiResult<KnowledgeChange>> =>
      request<KnowledgeChange>(`/api/v1/knowledge/${encodeURIComponent(knowledgeId)}/review`, {
        method: 'POST',
        body,
      }),

    /**
     * 知识检索（Copilot 知识秒查）：只回 published + 生效期内；`q` 缺失/超长 → 400 `E-1001`。
     * 无命中返回 `items: []`（200，不是错误）→ 页面必须拒答并转人工，不得编造答案。
     */
    search: (query: KnowledgeSearchQuery): Promise<ApiResult<KnowledgeSearchResult>> => {
      const params = new URLSearchParams({ q: query.q })
      params.set('limit', String(query.limit ?? KNOWLEDGE_SEARCH_DEFAULT_LIMIT))
      return request<KnowledgeSearchResult>(`/api/v1/knowledge/search?${params.toString()}`)
    },

    /** 知识与采纳统计（staff/it/admin；owner → 403 `E-2001`）：数字全部来自服务端 */
    stats: (query: { from?: string; to?: string } = {}): Promise<ApiResult<KnowledgeStats>> => {
      const params = new URLSearchParams()
      if (query.from) params.set('from', query.from)
      if (query.to) params.set('to', query.to)
      const suffix = params.toString() ? `?${params.toString()}` : ''
      return request<KnowledgeStats>(`/api/v1/knowledge/stats${suffix}`)
    },
  },

  /**
   * P6 W34 知识导入（契约 v1.5.0 §12 / task-87；设计 `docs/design/知识导入方案.md`）
   * -------------------------------------------------------------------------
   * | 前端方法 | 契约端点 | 使用页面 |
   * |---|---|---|
   * | `api.knowledgeImport.createPaste` | POST /api/v1/knowledge/imports（application/json） | 知识页「导入」页签 · 粘贴文本 / RAG 缺口沉淀（badcase） |
   * | `api.knowledgeImport.createFile` | POST /api/v1/knowledge/imports（multipart/form-data） | 知识页「导入」页签 · 单文件（txt/md/docx/pdf ≤10MB）/ ZIP 批量（≤50 文件/50MB） |
   * | `api.knowledgeImport.list` | GET /api/v1/knowledge/imports?status&page&pageSize | 导入任务列表（仅本项目可见；服务端分页） |
   * | `api.knowledgeImport.get` | GET /api/v1/knowledge/imports/{jobId} | 任务详情轮询（2s → preview_ready/failed）/ 预览页 |
   * | `api.knowledgeImport.commit` | POST /api/v1/knowledge/imports/{jobId}/commit | 预览确认提交（默认 pending_review；approved 仅 it/admin 直审） |
   * | `api.knowledgeImport.cancel` | POST /api/v1/knowledge/imports/{jobId}/cancel | 取消任务（pending/parsing/preview_ready；终态 409 E-3002） |
   *
   * 口径（前端不改写契约语义）：
   * - 权限：`knowledge.import`（steward/agent/supervisor/it/admin）；ZIP 批量另需 `knowledge.import.batch`；
   *   `approved` 直审**仅 it/admin**（前端隐藏 + 服务端 403 `E-2001` 兜底）；owner 创建任务 → 403；
   * - 限额：单文件 ≤10MB、粘贴 ≤200,000 字符、ZIP ≤50 文件/50MB、10 次/分/账号（超限 413 `E-1001`、
   *   超频 429 `E-4003`）—— 前端只做提示与预校验，真实边界在 Java（`KNOWLEDGE_IMPORT_LIMITS` 同源）；
   * - 解析是**同步**的（201 直接带预览；解析失败也是 201 + `status=failed` + `error`），
   *   页面仍按 2s 轮询直到 `preview_ready` / `failed`，避免把 in-flight 任务当终态；
   * - 提交是写操作：`Idempotency-Key` 可选，页面按「动作 + 载荷」复用同键重试；
   * - 上传/ZIP 走 multipart（`requestMultipart`，**不手工设 Content-Type**），JSON 粘贴走既有 `request`。
   */
  knowledgeImport: {
    /** 粘贴文本 / RAG 缺口沉淀（JSON 分支；201 + 预览态任务） */
    createPaste: (
      body: KnowledgeImportCreateRequest,
      idempotencyKey?: string,
    ): Promise<ApiResult<KnowledgeImportJob>> =>
      request<KnowledgeImportJob>('/api/v1/knowledge/imports', {
        method: 'POST',
        body,
        timeoutMs: KNOWLEDGE_IMPORT_CREATE_TIMEOUT_MS,
        idempotencyKey,
      }),

    /**
     * 单文件 / ZIP 批量（multipart 分支；201 + 预览态任务）。
     * `options.sourceType='batch'` 时按 ZIP 处理（需 `knowledge.import.batch`），
     * 且服务端会校验 ZIP 内文件数/解压总量；非 ZIP 文件仍走白名单 txt/md/docx/pdf。
     */
    createFile: (
      file: File,
      options: KnowledgeImportFileOptions = {},
      idempotencyKey?: string,
    ): Promise<ApiResult<KnowledgeImportJob>> => {
      const form = new FormData()
      form.append('file', file, file.name)
      if (options.title) form.append('title', options.title)
      if (options.category) form.append('category', options.category)
      if (options.sourceType) form.append('sourceType', options.sourceType)
      return requestMultipart<KnowledgeImportJob>('/api/v1/knowledge/imports', form, {
        timeoutMs: KNOWLEDGE_IMPORT_UPLOAD_TIMEOUT_MS,
        idempotencyKey,
      })
    },

    /** 任务列表（服务端分页；`status` 省略 = 全部；仅 actor 项目可见，跨项目 404/403） */
    list: (query: KnowledgeImportListQuery = {}): Promise<ApiResult<KnowledgeImportJobListResult>> => {
      const params = pageParams(query)
      if (query.status) params.set('status', query.status)
      const suffix = params.toString() ? `?${params.toString()}` : ''
      return request<KnowledgeImportJobListResult>(`/api/v1/knowledge/imports${suffix}`)
    },

    /** 任务详情（非本项目任务 → 404 `E-3001`，不区分无权限与不存在） */
    get: (jobId: string): Promise<ApiResult<KnowledgeImportJob>> =>
      request<KnowledgeImportJob>(`/api/v1/knowledge/imports/${encodeURIComponent(jobId)}`),

    /**
     * 提交预览（写操作）：省略 `status` = `pending_review`（待审，走既有 `/knowledge/{id}/review` 审核）；
     * `approved` 仅 it/admin（否则 403 `E-2001` `requiredCapability=knowledge.review`）；
     * `chunks` 按 preview.seq 只导入部分块（省略/空 = 全部）；非 `preview_ready` → 409 `E-3002`。
     */
    commit: (
      jobId: string,
      body: KnowledgeImportCommitRequest = {},
      idempotencyKey?: string,
    ): Promise<ApiResult<KnowledgeCommitResult>> =>
      request<KnowledgeCommitResult>(
        `/api/v1/knowledge/imports/${encodeURIComponent(jobId)}/commit`,
        {
          method: 'POST',
          body,
          timeoutMs: KNOWLEDGE_IMPORT_COMMIT_TIMEOUT_MS,
          idempotencyKey,
        },
      ),

    /** 取消任务（pending/parsing/preview_ready 可取消；终态 → 409 `E-3002`；不删除已归档原文） */
    cancel: (jobId: string): Promise<ApiResult<KnowledgeImportJob>> =>
      request<KnowledgeImportJob>(
        `/api/v1/knowledge/imports/${encodeURIComponent(jobId)}/cancel`,
        { method: 'POST', body: {} },
      ),
  },

  /**
   * P6 W33 知识库 RAG（契约 v1.4.0 §11 / task-81；设计 `docs/design/知识库RAG方案.md` §6/§11）
   * -------------------------------------------------------------------------
   * | 前端方法 | 契约端点 | 使用页面 |
   * |---|---|---|
   * | `api.rag.query` | POST /api/v1/rag/query | 知识页「智能问答」页签 + Copilot 知识秒查（同一组件复用） |
   * | `api.rag.health` | GET /api/v1/rag/health | 知识页「RAG 健康」页签（仅 it/admin；其它角色 403） |
   *
   * 口径（**前端不改写契约语义**）：
   * - 响应是 `RagQueryResult`：`answer`（可能含 `[n]` 引用）/ `sources[]` / `confidence` / `answerable` /
   *   `refusalReason?` / `mode` / `reranked` / `degraded?` / `usage?` / `traceId`；
   * - **拒答不是错误**：`answerable=false` 是 200 的正常结果（`refusalReason` 说明原因），页面转人话 + 转人工；
   * - `projectId` 缺省 = 当前 actor 项目（前端**不**默认注入项目作用域）；`category` / `topK` 为可选过滤；
   * - 超时口径：RAG 链路含编排层检索（BFF → `services/ai-orchestrator`，BFF 侧超时 8s + 规则兜底），
   *   普通请求 12s 默认值偏紧，这里显式放宽到 25s；超时仍按 `E-4001`（可重试）由页面给「超时」态。
   */
  rag: {
    /**
     * 知识问答（`POST /api/v1/rag/query`，能力 `rag.query`，全角色可用）：
     * - `question` 1—500 字符（缺失/超长 → 400 `E-1001`）；`topK` 1—10（默认 5）；
     * - 跨项目 `projectId` → 403 `E-2002`（`details.reason=cross_project`）；
     * - `answerable=false` 仍 200（拒答不是错误），页面按 `refusalReason` 转人话并给转人工入口。
     */
    query: (
      body: RagQueryRequest,
      options: { timeoutMs?: number; signal?: AbortSignal } = {},
    ): Promise<ApiResult<RagQueryResult>> =>
      request<RagQueryResult>('/api/v1/rag/query', {
        method: 'POST',
        body,
        timeoutMs: options.timeoutMs ?? RAG_QUERY_TIMEOUT_MS,
        ...(options.signal ? { signal: options.signal } : {}),
      }),

    /**
     * RAG 编排健康（`GET /api/v1/rag/health`）：**仅 it/admin**（`rag.health`），其它角色 403 `E-2001`；
     * 只读、不落审计。`status=degraded` 属正常演示态（如 reranker 关闭、LLM 无真实中转凭据），
     * 页面必须如实展示组件状态，不得把 PoC/本地向量库说成生产可用。
     */
    health: (options: { timeoutMs?: number } = {}): Promise<ApiResult<RagHealth>> =>
      request<RagHealth>('/api/v1/rag/health', { timeoutMs: options.timeoutMs ?? RAG_HEALTH_TIMEOUT_MS }),
  },

  /**
   * P4 W16 Copilot 8 工作区（契约 v1.2.0 §13 / P4-014）
   * -------------------------------------------------------------------------
   * | 前端方法 | 契约端点 | 使用页面 |
   * |---|---|---|
   * | `api.copilot.workspaces` | GET /api/v1/copilot/workspaces | Copilot 壳左栏目录/状态（8 项固定顺序） |
   * | `api.copilot.summary` | POST /api/v1/copilot/summary | 工单摘要工作区（≤5 行摘要 + ≤3 待办 + cites） |
   * | `api.copilot.script` | POST /api/v1/copilot/script | 话术生成工作区（3 版 + forbiddenHits 禁区扫描） |
   * | `api.copilot.portrait` | GET /api/v1/copilot/portrait?roomCode= | 业主画像工作区（**脱敏**：无手机号/金额明细） |
   * | `api.copilot.digest` | GET /api/v1/copilot/digest?date= | 日报摘要（数字来自服务端聚合） |
   * | `api.copilot.report` | GET /api/v1/copilot/report?from&to | 周期报表（metrics + breakdown） |
   * | `api.copilot.broadcast` | POST /api/v1/copilot/group/broadcast | 群服务人工确认群发（`confirmed:true`；回执 `status:mocked`） |
   *
   * 口径（AGENTS.md「不编造」+ README §7.4）：
   * - Copilot 结果是**平台规则组装**演示口径（`mock:true`），模型调用由 Python 编排层承接；
   *   页面必须标注「演示：规则组装，模型调用由 Python 编排层」，不得说成真实模型输出；
   * - 写操作只有群发：必须人工确认（`confirmed:true`），经 `services/mock-channel` 留痕，
   *   回执 `status=mocked` **不代表已真实送达业主**；不做群内自动回复；
   * - 画像/摘要/话术入参不得含手机号/地址明文（服务端脱敏，前端也不采集明文）。
   */
  copilot: {
    /** 8 工作区目录（固定顺序，不分页；`status` 决定入口可用性，前端不硬编码） */
    workspaces: (): Promise<ApiResult<CopilotWorkspaceListResult>> =>
      request<CopilotWorkspaceListResult>('/api/v1/copilot/workspaces'),

    /**
     * 工单摘要：`ticketNo` 或 `messages` 二选一（都缺 → 400 `E-1001`）。
     * `cites` 为空时页面必须提示「无出处，需人工确认」，不得补造引用。
     */
    summary: (body: CopilotSummaryRequest): Promise<ApiResult<CopilotSummary>> =>
      request<CopilotSummary>('/api/v1/copilot/summary', { method: 'POST', body }),

    /** 话术 3 版 + 禁区扫描（只生成不外发；`forbiddenHits` 非空不得直接复制外发） */
    script: (body: CopilotScriptRequest): Promise<ApiResult<CopilotScript>> =>
      request<CopilotScript>('/api/v1/copilot/script', { method: 'POST', body }),

    /** 脱敏画像（房号必填；不存在 → 404 `E-3001`；格式非法 → 400 `E-1001`） */
    portrait: (roomCode: string): Promise<ApiResult<CopilotPortrait>> =>
      request<CopilotPortrait>(`/api/v1/copilot/portrait?roomCode=${encodeURIComponent(roomCode)}`),

    /** 日报摘要（`date` 省略=服务端当日；无数据日期回空数组与 0 值，不编造） */
    digest: (query: { date?: string } = {}): Promise<ApiResult<CopilotDigest>> => {
      const params = new URLSearchParams()
      if (query.date) params.set('date', query.date)
      const suffix = params.toString() ? `?${params.toString()}` : ''
      return request<CopilotDigest>(`/api/v1/copilot/digest${suffix}`)
    },

    /** 周期报表（`from`/`to` 省略=最近 7 天；`from > to` → 400 `E-1001`） */
    report: (query: { from?: string; to?: string } = {}): Promise<ApiResult<CopilotReport>> => {
      const params = new URLSearchParams()
      if (query.from) params.set('from', query.from)
      if (query.to) params.set('to', query.to)
      const suffix = params.toString() ? `?${params.toString()}` : ''
      return request<CopilotReport>(`/api/v1/copilot/report${suffix}`)
    },

    /**
     * 群服务群发（**唯一写操作**，人工确认红线）：`confirmed` 必须 true（否则 400 `E-1001`）；
     * 回执 `pushIds` 为 mock 通道受理 ID、`status` 恒 `mocked`；页面必须标注
     * 「mock 通道受理，不代表已真实送达」。
     */
    broadcast: (body: CopilotBroadcastRequest): Promise<ApiResult<CopilotBroadcastResult>> =>
      request<CopilotBroadcastResult>('/api/v1/copilot/group/broadcast', { method: 'POST', body }),
  },

  /**
   * P4 W17 坐席工作台（契约 v1.2.0 §13 / P4-015）
   * -------------------------------------------------------------------------
   * | 前端方法 | 契约端点 | 使用页面 |
   * |---|---|---|
   * | `api.desk.queue` | GET /api/v1/desk/queue | 实时队列卡（等待时长 / SLA 倒计时 / 预览 + 汇总） |
   * | `api.desk.takeover` | POST /api/v1/desk/queue/{id}/takeover | 接管（互斥 → 409 `E-3002` while already_taken） |
   * | `api.desk.release` | POST /api/v1/desk/queue/{id}/release | 释放（仅本人接管；非本人 403） |
   * | `api.desk.sla` | GET /api/v1/desk/sla?from&to | SLA 看板（avg/breachRate/byHour，全部服务端算） |
   * | `api.desk.qaSamples` | GET /api/v1/desk/qa/samples?status&page&pageSize | 质检抽样列表（agent 只看自己） |
   * | `api.desk.qaScore` | POST /api/v1/desk/qa/samples/{id}/score | 质检评分 0—100（越界 400 / 重复 409） |
   * | `api.desk.qaStats` | GET /api/v1/desk/qa/stats | 质检统计（仅 supervisor/it/admin） |
   *
   * 口径：`waitingSeconds`/`slaDueAt` 由服务端计算，前端只做倒计时展示；接管是写操作且互斥，
   * 页面收到 409 必须给「已被他人接管」人话而不是静默失败；it/admin 只读（无接管/释放能力）。
   */
  desk: {
    /** 队列（只列 waiting；`items` 按等待时长降序 + `summary` 指标卡） */
    queue: (): Promise<ApiResult<DeskQueue>> => request<DeskQueue>('/api/v1/desk/queue'),

    /** 接管会话（写操作；已被接管 409 `E-3002`，`details.reason=already_taken`） */
    takeover: (
      conversationId: string,
      body: DeskTakeoverRequest = {},
    ): Promise<ApiResult<DeskTakeoverResult>> =>
      request<DeskTakeoverResult>(
        `/api/v1/desk/queue/${encodeURIComponent(conversationId)}/takeover`,
        { method: 'POST', body },
      ),

    /** 释放会话（写操作；仅当前接管人；状态不允许 409 `E-3002`） */
    release: (conversationId: string): Promise<ApiResult<DeskReleaseResult>> =>
      request<DeskReleaseResult>(
        `/api/v1/desk/queue/${encodeURIComponent(conversationId)}/release`,
        { method: 'POST', body: {} },
      ),

    /** SLA（`from`/`to` 省略=今天；`from > to` → 400 `E-1001`） */
    sla: (query: { from?: string; to?: string } = {}): Promise<ApiResult<DeskSla>> => {
      const params = new URLSearchParams()
      if (query.from) params.set('from', query.from)
      if (query.to) params.set('to', query.to)
      const suffix = params.toString() ? `?${params.toString()}` : ''
      return request<DeskSla>(`/api/v1/desk/sla${suffix}`)
    },

    /** 质检抽样列表（分页；`status` 可选 pending/scored） */
    qaSamples: (query: DeskQaSamplesQuery = {}): Promise<ApiResult<DeskQaSampleListResult>> => {
      const params = pageParams(query)
      if (query.status) params.set('status', query.status)
      return request<DeskQaSampleListResult>(`/api/v1/desk/qa/samples?${params.toString()}`)
    },

    /** 质检评分（写操作；`score` 0—100 整数，越界 400、重复评分 409、非本人抽样 403） */
    qaScore: (sampleId: string, body: DeskQaScoreRequest): Promise<ApiResult<DeskQaSample>> =>
      request<DeskQaSample>(`/api/v1/desk/qa/samples/${encodeURIComponent(sampleId)}/score`, {
        method: 'POST',
        body,
      }),

    /** 质检统计（管理视图：supervisor/it/admin；agent → 403 `E-2001`） */
    qaStats: (): Promise<ApiResult<DeskQaStats>> => request<DeskQaStats>('/api/v1/desk/qa/stats'),
  },

  /**
   * P4 W17 全链路回溯 + 证据包（契约 v1.2.0 §13 / P4-020）
   * -------------------------------------------------------------------------
   * | 前端方法 | 契约端点 | 使用页面 |
   * |---|---|---|
   * | `api.trace.search` | GET /api/v1/trace/search?actorId&action&from&to&page&pageSize | /trace 条件检索（按 lastAt 降序） |
   * | `api.trace.get` | GET /api/v1/trace/{traceId} | 时间线（entries 按 at 升序；无数据 404 `E-3001`） |
   * | `api.trace.evidenceJson` | GET /api/v1/trace/{traceId}/evidence?format=json | 证据包（JSON 信封：checksum/maskedFields） |
   * | `api.trace.evidenceCsv` | GET /api/v1/trace/{traceId}/evidence?format=csv | 证据包 CSV（**文件流例外**，真实下载 + 文件名） |
   *
   * 权限：`trace.read`（检索/详情）与 `trace.export`（证据包导出）**仅 supervisor/it/admin**；
   * 页面无权限时必须走 PermissionDenied（不展示未授权时间线/证据）。
   * 脱敏：时间线字段已由服务端脱敏（`maskedFields` 列出已脱敏字段），页面只标注、不还原。
   */
  trace: {
    /** 条件检索（筛选条件全部可选；返回每 traceId 一行 + 节点数） */
    search: (query: TraceSearchQuery = {}): Promise<ApiResult<TraceSearchResult>> => {
      const params = pageParams(query)
      if (query.actorId) params.set('actorId', query.actorId)
      if (query.action) params.set('action', query.action)
      if (query.from) params.set('from', query.from)
      if (query.to) params.set('to', query.to)
      const suffix = params.toString() ? `?${params.toString()}` : ''
      return request<TraceSearchResult>(`/api/v1/trace/search${suffix}`)
    },

    /** 链路详情（时间线；traceId 不存在 404 `E-3001`） */
    get: (traceId: string): Promise<ApiResult<TraceDetail>> =>
      request<TraceDetail>(`/api/v1/trace/${encodeURIComponent(traceId)}`),

    /** 证据包 JSON 信封（`checksum` + `maskedFields`；页面序列化后下载为 `.json`） */
    evidenceJson: (traceId: string): Promise<ApiResult<TraceEvidence>> =>
      request<TraceEvidence>(
        `/api/v1/trace/${encodeURIComponent(traceId)}/evidence?format=json`,
      ),

    /**
     * 证据包 CSV（文件流例外：2xx 直接是 `text/csv` 附件，文件名取 `Content-Disposition`）。
     * 403 `E-2001`（`details.requiredCapability=trace.export`）/ 404 `E-3001` 一律抛 {@link ApiError}。
     */
    evidenceCsv: async (traceId: string): Promise<TraceEvidenceExport> => {
      const file = await requestBlob(
        `/api/v1/trace/${encodeURIComponent(traceId)}/evidence?format=csv`,
      )
      return {
        traceId,
        filename: file.filename || `trace-${traceId}.csv`,
        contentType: file.contentType,
        size: file.blob.size,
        blob: file.blob,
      }
    },
  },

  /** CAP-06/07/08/09/13/18 报修与工单闭环 */
  ticket: {
    create: (body: TicketCreateRequest, idempotencyKey: string): Promise<ApiResult<TicketCreateResult>> =>
      request<TicketCreateResult>('/api/v1/tickets', { method: 'POST', body, idempotencyKey }),

    get: (ticketNo: string): Promise<ApiResult<Ticket>> =>
      request<Ticket>(`/api/v1/tickets/${encodeURIComponent(ticketNo)}`),

    /**
     * 工单列表：`status` / `team` / `category` / `dateFrom` / `dateTo` / `sort` 全部可选，
     * 分页 `page`/`pageSize` 由服务端返回（契约 v0.3.1 §ticketList）。
     */
    list: (query: TicketListQuery = {}): Promise<ApiResult<TicketListResult>> => {
      const params = new URLSearchParams()
      if (query.status) params.set('status', query.status)
      if (query.team) params.set('team', query.team)
      if (query.category) params.set('category', query.category)
      if (query.dateFrom) params.set('dateFrom', query.dateFrom)
      if (query.dateTo) params.set('dateTo', query.dateTo)
      if (query.sort) params.set('sort', query.sort)
      if (query.page) params.set('page', String(query.page))
      if (query.pageSize) params.set('pageSize', String(query.pageSize))
      const suffix = params.toString() ? `?${params.toString()}` : ''
      return request<TicketListResult>(`/api/v1/tickets${suffix}`)
    },

    /** 三次推送记录（v0.3.1；P2 W6 为 mock 通道留痕，`mock:true` 不代表真实外发） */
    pushes: (ticketNo: string): Promise<ApiResult<TicketPushListResult>> =>
      request<TicketPushListResult>(`/api/v1/tickets/${encodeURIComponent(ticketNo)}/pushes`),

    transition: (
      ticketNo: string,
      body: TicketTransitionRequest,
      idempotencyKey: string,
    ): Promise<ApiResult<TicketTransitionResult>> =>
      request<TicketTransitionResult>(`/api/v1/tickets/${encodeURIComponent(ticketNo)}/transition`, {
        method: 'POST',
        body,
        idempotencyKey,
      }),

    feedback: (
      ticketNo: string,
      body: TicketFeedbackRequest,
      idempotencyKey: string,
    ): Promise<ApiResult<TicketFeedbackResult>> =>
      request<TicketFeedbackResult>(`/api/v1/tickets/${encodeURIComponent(ticketNo)}/feedback`, {
        method: 'POST',
        body,
        idempotencyKey,
      }),

    handoffSummary: (ticketNo: string): Promise<ApiResult<HandoffSummary>> =>
      request<HandoffSummary>(`/api/v1/tickets/${encodeURIComponent(ticketNo)}/handoff-summary`),
  },

  /** P2-025 连接器注册表 + 18 项能力探测（契约 v0.3.2 §11.8.3） */
  connector: {
    /** 5 套 ERP 注册表摘要（`data` 直接是数组，能力计数取最近一次探测/声明） */
    list: (): Promise<ApiResult<ConnectorSummary[]>> => request<ConnectorSummary[]>('/api/v1/connectors'),

    /** 连接器详情：摘要 + 18 项 `CapabilityState`（未探测时 `capabilities: []`，不造数） */
    get: (connectorId: string): Promise<ApiResult<ConnectorDetail>> =>
      request<ConnectorDetail>(`/api/v1/connectors/${encodeURIComponent(connectorId)}`),

    /**
     * 触发能力探测（配置类动作，`Idempotency-Key` 可选）：
     * 200 → `ProbeReport`（18 项 states + summary.drift）；未接入连接器 → 503 `E-5001`（可解释错误）。
     */
    probe: (connectorId: string, idempotencyKey?: string): Promise<ApiResult<ProbeReport>> =>
      request<ProbeReport>(`/api/v1/connectors/${encodeURIComponent(connectorId)}/probe`, {
        method: 'POST',
        body: {},
        idempotencyKey,
      }),

    /** 最近一次探测日报 + 近 7 日历史（无历史时 `latest: null`、`history: []`） */
    probeLatest: (connectorId: string): Promise<ApiResult<ProbeLatestResult>> =>
      request<ProbeLatestResult>(`/api/v1/connectors/${encodeURIComponent(connectorId)}/probe/latest`),

    /** 探测日报分页历史（按 startedAt 倒序） */
    probes: (connectorId: string, query: PageQuery = {}): Promise<ApiResult<ProbeListResult>> => {
      const params = pageParams(query)
      return request<ProbeListResult>(
        `/api/v1/connectors/${encodeURIComponent(connectorId)}/probes?${params.toString()}`,
      )
    },

    /**
     * P5 W19 连接器 SDK 声明（契约 v1.3.0 `GET /connectors/{id}/sdk`）：`sdkVersion` /
     * 声明的标准能力 `capabilities[]` / 映射字段数 `fieldCount` / 运行限制 `limits{timeoutMs,pageSizeMax,rateLimitPerMin}`。
     * **声明先于探测、探测覆盖声明**：能力是否可用仍以 `/probe` 结果为准，页面不得据声明对外承诺。
     * 连接器未注册 → 404 `E-3001`；`connectorId` 非法 → 400 `E-1001`。
     */
    sdk: (connectorId: string): Promise<ApiResult<ConnectorSdk>> =>
      request<ConnectorSdk>(`/api/v1/connectors/${encodeURIComponent(connectorId)}/sdk`),
  },

  /** P2-024 映射版本化（契约 v0.3.2 §11.8.2；P5 W19 增 `connectorId` 筛选） */
  mapping: {
    /**
     * 映射版本列表（`data` 直接是数组；状态 draft → trial_passed → published → rolled_back）。
     * P5 W19：传 `connectorId` 时只返回该连接器的映射（`GET /mappings?connectorId=`），
     * 映射是**连接器作用域**资源：跨连接器操作由服务端 403 `E-2002` `cross_connector` 兜底。
     */
    list: (query: MappingListQuery = {}): Promise<ApiResult<MappingVersion[]>> => {
      const suffix = query.connectorId ? `?connectorId=${encodeURIComponent(query.connectorId)}` : ''
      return request<MappingVersion[]>(`/api/v1/mappings${suffix}`)
    },

    /** 映射版本详情（含 `fields[]`：源字段 ↔ 标准字段 + 转换规则） */
    get: (mappingId: string): Promise<ApiResult<MappingVersion>> =>
      request<MappingVersion>(`/api/v1/mappings/${encodeURIComponent(mappingId)}`),

    /** 试跑（默认 200 样本；通过后 draft/rolled_back → trial_passed，试跑失败不改状态） */
    trial: (mappingId: string, sampleSize?: number, idempotencyKey?: string): Promise<ApiResult<MappingTrialReport>> =>
      request<MappingTrialReport>(`/api/v1/mappings/${encodeURIComponent(mappingId)}/trial`, {
        method: 'POST',
        body: sampleSize === undefined ? {} : { sampleSize },
        idempotencyKey,
      }),

    /** 发布（仅 trial_passed 可发布；否则 409 `E-3002`，details.allowed 给合法动作） */
    publish: (mappingId: string, idempotencyKey?: string): Promise<ApiResult<MappingVersion>> =>
      request<MappingVersion>(`/api/v1/mappings/${encodeURIComponent(mappingId)}/publish`, {
        method: 'POST',
        body: {},
        idempotencyKey,
      }),

    /** 回滚（toVersion 省略 = 上一个已发布版本；无可用版本 → 409 `E-3002`） */
    rollback: (
      mappingId: string,
      body: MappingRollbackRequest = {},
      idempotencyKey?: string,
    ): Promise<ApiResult<MappingVersion>> =>
      request<MappingVersion>(`/api/v1/mappings/${encodeURIComponent(mappingId)}/rollback`, {
        method: 'POST',
        body,
        idempotencyKey,
      }),
  },

  /** P2-025 降级台账（连接器 × 能力 × 替代路径 命中聚合；只读） */
  degrade: {
    ledger: (query: DegradeLedgerQuery = {}): Promise<ApiResult<DegradeLedgerResult>> => {
      const params = pageParams(query)
      if (query.connectorId) params.set('connectorId', query.connectorId)
      if (query.from) params.set('from', query.from)
      if (query.to) params.set('to', query.to)
      return request<DegradeLedgerResult>(`/api/v1/degrade/ledger?${params.toString()}`)
    },
  },

  /** P2-013/P2-027 只读对账（异步受理 → 报告轮询；历史列表） */
  recon: {
    /**
     * 触发对账：可传 scope（默认 `bill`，抽样 200）或完整 `ReconRunRequest`。
     * 202 受理返回 `runId`，随后轮询 `report(runId)`。
     */
    run: (
      input: ReconScope | ReconRunRequest = 'bill',
      idempotencyKey?: string,
    ): Promise<ApiResult<ReconRunResult>> => {
      const body: ReconRunRequest =
        typeof input === 'string' ? { scope: input, sampleSize: DEFAULT_RECON_SAMPLE_SIZE } : input
      return request<ReconRunResult>('/api/v1/recon/run', { method: 'POST', body, idempotencyKey })
    },
    report: (runId: string): Promise<ApiResult<ReconReport>> =>
      request<ReconReport>(`/api/v1/recon/report/${encodeURIComponent(runId)}`),
    /**
     * 对账差异 CSV 导出（P3-031，契约 v1.1.0 `reconReportExport`）：
     * - 2xx：`text/csv` 文件流（**不是**统一 JSON 信封），文件名取 `Content-Disposition`
     *   （缺省 `recon-<runId>.csv`）；
     * - 403：`E-2001` + `details.requiredCapability=recon.report`（supervisor/it/admin 才可导出）；
     * - 404：`E-3001`（runId 不存在）；409：`E-3002`（报告未完成，`details.reason=report_not_completed`）；
     * - 失败一律抛 {@link ApiError}，页面按 `forbiddenDetailsOf()` / 错误码给人话，不伪造下载成功。
     */
    exportReport: async (runId: string): Promise<ReconReportExport> => {
      const file = await requestBlob(`/api/v1/recon/report/${encodeURIComponent(runId)}/export?format=csv`)
      return {
        runId,
        filename: file.filename || `recon-${runId}.csv`,
        contentType: file.contentType,
        size: file.blob.size,
        blob: file.blob,
      }
    },
    /** 对账历史（分页，按 startedAt 倒序；明细走 report(runId)） */
    runs: (query: PageQuery = {}): Promise<ApiResult<ReconRunListResult>> => {
      const params = pageParams(query)
      return request<ReconRunListResult>(`/api/v1/recon/runs?${params.toString()}`)
    },
  },

  /**
   * P5 W19 灰度与双跑（契约 v1.3.0 §14，架构 §24.4）
   * - `policies`：全量小集合（`data` 直接是数组 ≤50）；`percent` 0—100 为新版本流量比例，
   *   `enabled=false` 暂停策略（等价全量回旧版，回滚 ≤5 分钟）；
   * - `createPolicy` / `updatePolicy`：**仅 it/admin**（能力 `gray.policy.manage`），写操作落审计
   *   `gray.policy.create` / `gray.policy.update`（资源类型 `gray_policy`）；重复 scope+targetId → 409 `E-3002`；
   * - `compare`：双跑只读比对（`from`/`to` 必填），逐能力返回 checked/matched/diffCount/diffRate，
   *   **不写业务主数据、不改流量比例**；差异只作发布决策证据。
   */
  gray: {
    policies: (): Promise<ApiResult<GrayPolicy[]>> => request<GrayPolicy[]>('/api/v1/gray/policies'),

    createPolicy: (
      body: GrayPolicyCreateRequest,
      idempotencyKey?: string,
    ): Promise<ApiResult<GrayPolicy>> =>
      request<GrayPolicy>('/api/v1/gray/policies', { method: 'POST', body, idempotencyKey }),

    updatePolicy: (
      policyId: string,
      body: GrayPolicyUpdateRequest,
      idempotencyKey?: string,
    ): Promise<ApiResult<GrayPolicy>> =>
      request<GrayPolicy>(`/api/v1/gray/policies/${encodeURIComponent(policyId)}`, {
        method: 'PATCH',
        body,
        idempotencyKey,
      }),

    compare: (query: GrayCompareQuery): Promise<ApiResult<GrayCompareResult>> => {
      const params = new URLSearchParams({ from: query.from, to: query.to })
      if (query.connectorId) params.set('connectorId', query.connectorId)
      return request<GrayCompareResult>(`/api/v1/gray/compare?${params.toString()}`)
    },
  },

  /**
   * P5 W19 事件查询（契约 v1.3.0 §14，架构 §24.6 outbox / 死信队列）
   * - `list`：`type` / `projectId` / `from` / `to` 筛选 + `page`/`pageSize` 分页，按 `occurredAt` 倒序；
   * - 只读端点（不落审计）；权限 supervisor/it/admin（能力 `event.read`），其它角色 → 403 `E-2001`；
   * - `status`：pending 待投递 / published 已投递 / failed 可重试 / dead 死信（不静默丢失）。
   */
  event: {
    list: (query: EventListQuery = {}): Promise<ApiResult<EventListResult>> => {
      const params = pageParams(query)
      if (query.type) params.set('type', query.type)
      if (query.projectId) params.set('projectId', query.projectId)
      if (query.from) params.set('from', query.from)
      if (query.to) params.set('to', query.to)
      const suffix = params.toString() ? `?${params.toString()}` : ''
      return request<EventListResult>(`/api/v1/events${suffix}`)
    },
  },
}

/** 分页参数（契约 `PageQuery`：page 从 1 开始，pageSize 默认 20/上限 100） */
function pageParams(query: PageQuery = {}): URLSearchParams {
  const params = new URLSearchParams()
  if (query.page) params.set('page', String(query.page))
  if (query.pageSize) params.set('pageSize', String(query.pageSize))
  return params
}

export { ApiError } from './client'
export type { ApiRequestOptions, ApiResult, BlobResult, ForbiddenDetails } from './client'
export { forbiddenDetailsOf, parseContentDispositionFilename, unauthorizedReasonOf } from './client'

/** P5 W19：项目作用域（请求层注入 + 持久化键；页面/store 共用同一口径） */
export {
  PROJECT_SCOPE_STORAGE_KEY,
  isProjectScopedPath,
  readProjectScope,
  withProjectScope,
  writeProjectScope,
} from './project-scope'

/** P1 遗留只读类型（12 屏 mock）；P2 页面请用下方 contract 类型 */
export type { Health, ScreenKey, ScreenMeta, ScreenMock } from './types'

/**
 * 契约类型与常量（Re-export 自 `packages/shared` §11，单一事实源）：
 * 页面从 `@/api` 取类型，不再各写一套；`TICKET_STATUS_LABELS` 等常量也走这里。
 */
export * from './contract'

/** 错误码 / 降级 → 人话（薄封装 `src/content` 既有能力，不改 content 内部实现） */
export {
  degradedText,
  errorNextStep,
  errorText,
  errorTone,
  formatIsoMinute,
  formatIsoTime,
  formatMoney,
  isDegraded,
  isErrorCode,
  mockBadgeText,
} from './error-copy'
