# property-ai-service · 物业 AI 智能客服平台

**面向物业场景的 AI 智能客服系统**：覆盖业主报修、费用查询与催缴、投诉处理、知识问答、坐席 Copilot 与运营管理，
由 Vue 3 前端、Java 21 / Spring Boot 3 BFF、Python AI 编排层（含 RAG）组成，契约优先、可回归、可演示。

<p>
  <img alt="Vue3" src="https://img.shields.io/badge/Vue-3.x-42b883">
  <img alt="TypeScript" src="https://img.shields.io/badge/TypeScript-5.x-3178c6">
  <img alt="Java" src="https://img.shields.io/badge/Java-21-orange">
  <img alt="Spring Boot" src="https://img.shields.io/badge/Spring%20Boot-3.4-6db33f">
  <img alt="Python" src="https://img.shields.io/badge/Python-3.11-3776ab">
  <img alt="RAG" src="https://img.shields.io/badge/RAG-hybrid%20retrieval%20%2B%20LLM--rerank-8957e5">
  <img alt="OpenAPI" src="https://img.shields.io/badge/OpenAPI-v1.4.0-6ba539">
</p>

> 本仓库仅包含**核心业务代码**（前端 / BFF / AI 编排层 / 共享契约类型）。Mock 服务、测试与门禁脚本、演示语料、
> 设计文档与验收证据属于交付资产，不在本仓库内。
> 截图中的账号、房号、金额、知识条目均为**演示数据**；ERP / 推送 / 支付等外部系统均为 mock 或占位降级，未接真实生产通道。

---

## 目录

- [页面预览](#页面预览)
- [核心能力](#核心能力)
- [系统架构](#系统架构)
- [技术栈](#技术栈)
- [目录结构](#目录结构)
- [快速开始](#快速开始)
- [关键设计](#关键设计)
- [质量与验收](#质量与验收)
- [边界与已知限制](#边界与已知限制)
- [许可](#许可)

---

## 页面预览

> 截图来自真实运行的前端（Playwright 实跑采集），使用演示账号与演示数据。

<table>
  <tr>
    <td width="50%"><img src="assets/screenshots/01-login.webp" alt="登录" /><br/><sub><b>登录</b>：演示账号（业主 / 管家 / 坐席 / 主管 / 平台）</sub></td>
    <td width="50%"><img src="assets/screenshots/02-owner-home.webp" alt="业主端首页" /><br/><sub><b>业主端首页</b>：报修、账单、投诉、通知入口</sub></td>
  </tr>
  <tr>
    <td><img src="assets/screenshots/03-dashboard-overview.webp" alt="运营总览" /><br/><sub><b>运营总览</b>：工单 / 费用 / 满意度关键指标</sub></td>
    <td><img src="assets/screenshots/04-ticket-progress.webp" alt="工单进度" /><br/><sub><b>工单进度</b>：7 节点时间线、处理人、预计上门时间</sub></td>
  </tr>
  <tr>
    <td><img src="assets/screenshots/05-billing.webp" alt="费用账单" /><br/><sub><b>费用账单</b>：费项明细、分期/滞纳金、账单解释引用</sub></td>
    <td><img src="assets/screenshots/06-dunning.webp" alt="催缴" /><br/><sub><b>催缴</b>：催缴脚本预览、人工确认发送、频次红线</sub></td>
  </tr>
  <tr>
    <td><img src="assets/screenshots/07-complaint.webp" alt="投诉" /><br/><sub><b>投诉工作台</b>：6 态流转、SLA 倒计时、升级</sub></td>
    <td><img src="assets/screenshots/08-desk-queue.webp" alt="坐席队列" /><br/><sub><b>坐席实时队列</b>：等待时长、接管/释放、SLA 监控</sub></td>
  </tr>
  <tr>
    <td><img src="assets/screenshots/09-copilot.webp" alt="Copilot" /><br/><sub><b>Copilot 工作区</b>：8 个辅助工作区（摘要/话术/画像/日报/群发/知识/报表/交接）</sub></td>
    <td><img src="assets/screenshots/10-knowledge.webp" alt="知识运营" /><br/><sub><b>知识运营</b>：状态分布、版本与生效期、变更审核、badcase</sub></td>
  </tr>
  <tr>
    <td><img src="assets/screenshots/15-rag-answer.webp" alt="RAG 问答" /><br/><sub><b>RAG 知识问答（生成模式）</b>：答案带 <code>[n]</code> 引用，来源卡片可追溯</sub></td>
    <td><img src="assets/screenshots/16-rag-idle.webp" alt="RAG 空态" /><br/><sub><b>知识秒查入口</b>：示例问题、模式说明与能力提示（空态）</sub></td>
  </tr>
  <tr>
    <td><img src="assets/screenshots/11-erp-connector.webp" alt="连接器" /><br/><sub><b>ERP 适配中心</b>：连接器能力三态、SDK 声明（sdkVersion/limits/字段数）</sub></td>
    <td><img src="assets/screenshots/12-connector-matrix.webp" alt="连接器对比" /><br/><sub><b>多连接器能力对比</b>：18 项能力矩阵与选型建议</sub></td>
  </tr>
  <tr>
    <td><img src="assets/screenshots/13-gray-compare.webp" alt="灰度双跑" /><br/><sub><b>灰度与双跑</b>：策略百分比、双跑差异率、回滚</sub></td>
    <td><img src="assets/screenshots/14-project-switcher.webp" alt="项目切换" /><br/><sub><b>多项目隔离</b>：项目切换器，切换后全页重拉不串数据</sub></td>
  </tr>
  <tr>
    <td><img src="assets/screenshots/17-trace-timeline.webp" alt="全链路回溯" /><br/><sub><b>全链路回溯</b>：一次问答的检索/生成/降级证据时间线</sub></td>
    <td></td>
  </tr>
</table>

---

## 核心能力

### 业主端（Owner）
- **智能报修**：一句话描述 + 图片/语音入口 → 建单、看进度、改约、催办（仅限本户，数据范围由 BFF 强制）
- **费用与账单**：账单明细、分期/滞纳金说明、账单解释（带知识引用）、缴费跳转占位（不接真实支付）
- **投诉与建议**：提交、查看处理进度、SLA 超时升级提示
- **知识问答（RAG）**：带出处回答；证据不足明确拒答并转人工

### 员工端（管家 / 坐席 / 主管）
- **工单闭环**：7 节点状态机（受理 → 派单 → 上门 → 完工 → 回访 → 关闭 → 取消），派单/转派/改约/回写、三次推送留痕
- **费用与催缴**：欠费分桶（D1/D7/D15/D30）、催缴脚本生成、违禁话术扫描、人工确认后发送、频次红线
- **投诉处理**：6 态流转（受理/处理中/待回访/已回访/关闭/升级）、SLA（L1 30min / L2 24h / L3 2h）、关联工单
- **坐席工作台**：实时队列、超时提醒、会话接管/释放、质检评分、SLA 统计
- **Copilot 8 工作区**：工单摘要、沟通话术、业主画像、日报周报、群服务消息（人工确认群发）、知识沉淀、报表解读、人机交接
- **全链路回溯**：按 traceId 还原检索 → 生成 → 引用 → 降级 → 人工介入证据链

### 平台端（IT / 管理员）
- **ERP 适配中心**：多连接器注册与探测、能力三态矩阵、字段映射、版本化发布/试跑/回滚、单连接器 SDK 元数据
- **灰度与双跑**：按连接器/能力灰度百分比、新旧链路双跑差异对比、一键回滚
- **多项目隔离**：项目维度数据/知识/配置隔离，跨项目访问返回 `E-2002 cross_project`
- **可观测性**：请求/错误/缓存/连接器健康/事件积压指标，链路健康检查
- **知识与 RAG 运营**：知识变更审核、生效期管理、索引重建、RAG 评测门禁

### AI 能力（RAG）
- **混合检索**：向量召回 + BM25 字面召回，RRF 融合，元数据前置过滤（项目/状态/生效期）
- **LLM-as-rerank**：候选编号排序（RankGPT 式），低置信条件触发、超时/失败原路透传
- **四约束生成**：仅用证据作答、结论标 `[n]`、资料不足说「未收录」、归纳不照抄
- **引用硬校验 + 拒答**：答案引用必须 ⊆ 证据且为 published/生效期；无证据/低置信/引用越界 → `answerable=false` + 转人工
- **多级降级**：编排层不可用 → 规则检索；向量库不可用 → BM25；模型超时/无凭据 → 仅检索模式（不编造）

---

## 系统架构

```mermaid
flowchart LR
  subgraph Client["接入层"]
    W["Vue3 Web"]
    MP["微信小程序测试壳"]
  end
  subgraph BFF["Java 21 · Spring Boot 3"]
    API["REST API + JWT + 权限矩阵"]
    DOM["领域服务 - 工单/费用/投诉/知识/坐席/回溯"]
    ADP["适配中心 - 连接器/映射/灰度/项目/审计"]
    RAGAPI["RAG 接线 /api/v1/rag"]
  end
  subgraph AI["Python 3.11 · AI 编排层"]
    RAG["RAG Pipeline - 检索/重排/生成/引用校验"]
    IDX["索引管道 - 切分/嵌入/增量索引"]
  end
  subgraph Ext["外部系统 - mock/占位"]
    ERP["ERP - 用友 NC / 金蝶"]
    CH["推送通道 - 企微/短信/站内"]
    VS["向量库 - Chroma / pgvector"]
    LLM["公司中转 LLM"]
  end
  W --> API
  MP -.-> API
  API --> DOM
  DOM --> ADP
  API --> RAGAPI
  RAGAPI --> RAG
  ADP --> ERP
  DOM --> CH
  RAG --> VS
  RAG --> LLM
  IDX --> VS
```

**分层职责**
| 层 | 职责 | 关键约束 |
|---|---|---|
| 前端 | 页面/交互/状态管理、权限显隐、降级与空态展示 | 不在前端做权限判定与数据拼装 |
| Java BFF | 鉴权、权限矩阵、项目隔离、业务编排、审计、契约映射 | **不直连模型 SDK**；模型能力统一走 AI 编排层 |
| Python 编排层 | 检索、重排、Prompt 组装、模型调用、引用校验、拒答 | 输出必须可溯源，不允许无出处作答 |
| 适配中心 | 连接器 SPI、字段映射、灰度、对账 | 第三方连接器按同一 SDK 注册；写操作默认只读/回写待确认 |
| 外部系统 | ERP / 推送 / 支付 / 向量库 / LLM | 演示环境为 mock；密钥仅环境变量注入 |

---

## 技术栈

| 层 | 选型 | 说明 |
|---|---|---|
| 前端 | Vue 3 + TypeScript + Vite 5 + Pinia | 12 屏业务界面 + 8 个 Copilot 工作区；设计 token 统一 |
| BFF | Java 21 + Spring Boot 3.4 + Maven | REST + 领域服务 + 连接器运行时 + 审计 |
| AI 编排 | Python 3.11（自研轻编排） | 检索/重排/生成/评测；`openai` 兼容客户端调用公司中转站 |
| 向量检索 | ChromaDB（本地）/ pgvector（生产预留） | 接口抽象，切换实现不改调用方 |
| 关键词检索 | BM25（中文 char-bigram + 业务词典） | 补足数字/条款号/专名召回 |
| 重排 | **LLM-as-rerank**（RankGPT 式） | 零新增推理依赖；失败/超时透传不阻塞 |
| 契约 | OpenAPI 唯一事实源 → TS 类型 + Java DTO | 契约变更走版本化 + 一致性检查 |
| 鉴权 | JWT + 角色能力矩阵 | 角色：owner / steward / agent / supervisor / it / admin |

---

## 目录结构

```text
apps/
  web/                          Vue 3 前端
    src/
      views/                    业务页面（owner/billing/complaint/desk/knowledge/copilot/trace/erp/rag…）
      components/               通用组件（含 rag/ 问答与健康面板）
      api/                      请求层与契约类型出口
      auth/                     会话与能力码
      stores/                   Pinia
  server-java/
    src/main/java/com/property/ai/
      bff/api/                  REST 控制器（ticket/owner/bill/fee/complaint/dunning/knowledge/copilot/desk/trace/connector/mapping/gray/rag…）
      domain/                   领域服务（ticket/fee/dunning/complaint/knowledge/copilot/desk/trace/project/gray/channel/event）
      connector/                连接器 SPI 与实现（用友 NC / 金蝶 / 自研形态）
      security/ audit/ storage/ cache/ recon/
    src/main/resources/         application.yml
    pom.xml / mvnw
packages/shared/                共享契约 TS 类型/常量
services/ai-orchestrator/
  main.py                       编排层入口
  rag/                          检索、重排、生成、索引、评测入口（config/llm/embedder/chunker/store/bm25/hybrid/reranker/generation/…）
assets/screenshots/             README 展示用截图（演示数据）
```

---

## 快速开始

### 环境要求
- Node.js ≥ 20、pnpm 9
- JDK 21、Maven（仓库自带 `mvnw`）
- Python 3.11（AI 编排层，可选）

### 构建

```bash
# 前端 + 共享类型
pnpm install
pnpm --filter @property/shared build
pnpm --filter @property/web build

# Java BFF
pnpm build:server        # 等价于 apps/server-java/mvnw -DskipTests package
pnpm test:server         # Java 单测
```

### 本地启动（需自备 ERP/推送测试替身或联调环境）

```bash
# Java BFF（默认 :4000）
export RAG_INTERNAL_TOKEN=dev-rag-token          # PowerShell: $env:RAG_INTERNAL_TOKEN='dev-rag-token'
pnpm dev:server
# 端口被系统占用时可换端口：SERVER_PORT=4180

# 前端（:5173，代理到后端 4000；可用 VITE_API_TARGET 覆盖）
pnpm dev:web

# AI 编排层（可选，:4110；不启动时 Java 自动降级为规则检索）
cd services/ai-orchestrator
RAG_INTERNAL_TOKEN=dev-rag-token \
  python -m rag.service --port 4110 --env-file /path/to/your.env --rerank off
```

`application.yml` 中的 ERP / 通道地址默认指向本地测试替身（`:4200` / `:4300` / `:4400`）；
本仓库不含这些 mock 服务，请使用交付包中的 mock 服务或将地址指向可用的联调环境。

### 演示账号（交付包演示环境）

| 角色 | 账号 | 密码 |
|---|---|---|
| 业主 | `owner-3-2-1802` | `demo123` |
| 管家 | `steward-01` | `demo123` |
| 坐席 | `agent-01` | `demo123` |
| 主管 | `supervisor-01` | `demo123` |
| 平台 IT / 管理员 | `it-01` / `admin-01` | `demo123` |

RAG 问答入口：`/rag`（全角色）、知识页「智能问答 / RAG 健康」页签。

---

## 关键设计

### 1. 报修工单状态机（7 节点）

```
ACCEPTED → ASSIGNED → ON_SITE → COMPLETED → REVIEWED → CLOSED
    │           │           │
    └───────────┴───────────┴──────→ CANCELLED
```

- 每次流转写审计（操作人 / 前后状态 / 来源 / traceId），回写 ERP 为「待确认」而非直接成功；
- 推送三次（建单 / 派单 / 完工）落推送留痕表，失败可重试，群消息需人工确认；
- 业主仅能操作本户工单（数据范围在 BFF 强制，不依赖前端隐藏）。

### 2. 鉴权与数据隔离

| 机制 | 说明 |
|---|---|
| JWT | 演示环境签发；生产接 SSO（待接入） |
| 能力矩阵 | 角色 × 能力码（如 `ticket.assign`、`fee.catalog.change`、`rag.query`、`observability.read`），服务端强校验 |
| 403 区分 | 能力不足 → `E-2001 capability_denied`；跨项目 → `E-2002 cross_project`（`details.reason` 区分） |
| 项目隔离 | 列表/详情支持 `projectId` 作用域；跨项目默认拒绝并记审计 `access.deny` |

### 3. RAG 链路

```mermaid
flowchart TD
  Q["用户问题"] --> F["元数据过滤 - 项目/状态/生效期"]
  F --> V["向量召回 topK"]
  F --> B["BM25 召回 topK"]
  V --> RRF["RRF 融合 k=60"]
  B --> RRF
  RRF --> RR["LLM-as-rerank - 低置信触发 / 失败透传"]
  RR --> CTX["上下文排版 - 来源/分类/标题/相似度"]
  CTX --> GEN["四约束生成 - 标注引用编号"]
  GEN --> CHK["引用硬校验 - 编号属于证据且已生效"]
  CHK -->|通过| ANS["answer + sources + confidence"]
  CHK -->|不通过或无证据| REF["answerable=false 拒答并转人工"]
```

| 降级场景 | 行为 |
|---|---|
| LLM 凭据缺失 / 超时 | 仅检索模式：返回证据原文 + `[n]`，不生成、不编造 |
| 重排失败 / 超时（1.5s） | 原序透传，正常作答 |
| 向量库不可用 | BM25-only 检索 |
| 编排层不可用 | Java 回退规则检索（`mode=rules` + `degraded` 提示） |

**检索/生成评测门禁**（交付包执行）：Hit@5、MRR、负样本拒答率、引用精确率、faithfulness（LLM-as-judge）、跨项目零召回。

### 4. 契约优先

- OpenAPI 为唯一事实源，变更走「契约 → TS 类型 → Java DTO → 一致性检查」；
- 统一响应信封 `{ ok, data?, error?, degraded?, traceId? }`，错误码固定 17 类，不新造字符串；
- 前端类型全部 re-export 契约类型，禁止分叉手写。

---

## 质量与验收

> 以下为交付阶段的实测基线（证据与原始日志在交付包 `_dev/reports` 与测试产物中；本仓库不含测试代码）。

| 项 | 基线 |
|---|---|
| Java 单测 | **1004** 通过 |
| Python（RAG）单测 | **261** 通过 |
| Web 单测 | **79** 通过 |
| 契约一致性 | **5029** 断言通过（OpenAPI ↔ shared ↔ Java DTO） |
| E2E（Playwright，串行） | **221 passed / 0 failed / 4 skipped** |
| 安全回归 | **183/183**（401/403 矩阵、跨项目隔离、审计留痕） |
| 死按钮审计 | **0 dead** |
| 视觉回归 | 布局回归 **0**（动态数据/时间态差异单列说明） |
| 对账 | 账单/房档/工单各 200 条，差异率 0 |
| RAG 检索 | Hit@5 1.0（语料饱和）、MRR 0.9803、跨项目泄漏 0、过期泄漏 0 |
| RAG 安全 | 注入/越权/PII/审计不含正文 全通过（1 项日志抽查因无文件日志 SKIP） |

---

## 边界与已知限制

- **外部系统为 mock/占位**：ERP（用友/金蝶）、推送（企微/短信/站内）、支付/发票/短信通道均未接真实生产系统；
- **存储为进程内实现**：PostgreSQL / Redis / Kafka 仅提供接口、迁移方案与降级语义，无真实实例；
- **RAG 生成能力依赖公司中转模型凭据**：未授权时自动降级为仅检索模式；推理型模型延迟较高（实测 ≈19s），
  因此演示态常走检索/规则降级（这是**正确的降级**，不是故障）；
- **真实上线项**（小程序正式版/类目、等保测评、生产通道、SSO、Prometheus 导出）需客户资质与凭据，
  仓库与演示环境不声称已完成；
- 演示数据均为虚构（房号/金额/知识条目），不含真实业主 PII。

---

## 许可

仓库当前未声明开源许可证；如需对外开源请补充 `LICENSE` 并确认演示数据的授权。
