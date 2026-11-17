# 前端 API SDK（OpenAPI 同步）

- 契约源：`../../docs/openapi/openapi.yaml`（**v0.3.0 P2 冻结**，唯一事实源）
- 入口：`src/api/index.ts`
- 契约类型/常量：`src/api/contract.ts`（re-export `packages/shared/src/index.ts` §11，**不手写、不复制**）
- P1 遗留类型：`src/api/types.ts`（12 屏 mock，仅 `health/listScreens/getScreen` 用）
- 错误码/降级人话：`src/api/error-copy.ts`（薄封装 `src/content` 的 17 类错误码人话，不改 content 内部实现）
- 幂等键：`src/composables/useIdempotencyKey.ts`（`ui-<uuid>`，同一动作重试复用同键）
- 业主会话态：`src/composables/useOwnerSession.ts`（核验 5 分钟会话 / 账单 / 房档）
- 开发代理：`vite.config.ts` 将 `/api`、`/healthz` 代理到 `http://localhost:4000`（Java BFF；**不直连 mock-erp 4200**）
- 生产：由网关/BFF 提供同路径，`VITE_API_BASE` 可覆盖

## 使用

```ts
import { api, ApiError, degradedText, errorText } from '@/api'

const { data: bill, degraded } = await api.bill.query({ roomCode: '3-2-1802', period: '2026-11' })
const { data: ticket } = await api.ticket.get('WO-20261114-0832')

// 写操作必须带幂等键（同一动作重试复用同键）
const key = idempotency.ensure(actionSeed('ticket.create', payload))
const { data: created } = await api.ticket.create(payload, key)

try {
  await api.ticket.feedback('WO-…', { score: 5 }, key2)
} catch (error) {
  if (error instanceof ApiError) toast.error(`${errorText(error.code)}；${degradedText(error.degraded)}`)
}
```

## P2 端点映射

| 前端方法 | 契约端点 | 使用页面 |
|---|---|---|
| `api.owner.verify` | `POST /api/v1/owner/verify` | 业主端（核验 chips） |
| `api.room.get` | `GET /api/v1/rooms/{roomCode}` | 业主端会话 / 坐席上下文 |
| `api.bill.query` | `GET /api/v1/bills?roomCode=&period=` | 业主端账单卡（金额全部来自 ERP） |
| `api.ticket.create` | `POST /api/v1/tickets`（Idempotency-Key 必填） | 业主端 5 问报修建单 |
| `api.ticket.get` | `GET /api/v1/tickets/{ticketNo}` | 进度页 7 节点（`?ticket=` 深链） |
| `api.ticket.list` | `GET /api/v1/tickets?status=&team=&page=&pageSize=` | 坐席队列 / Copilot 选单 |
| `api.ticket.transition` | `POST /api/v1/tickets/{ticketNo}/transition` | 进度页改约/催办、业主工单卡 |
| `api.ticket.feedback` | `POST /api/v1/tickets/{ticketNo}/feedback` | 进度页评价服务 |
| `api.ticket.handoffSummary` | `GET /api/v1/tickets/{ticketNo}/handoff-summary` | 坐席同屏摘要 / Copilot 工单摘要 |
| `api.recon.run/report` | `POST /api/v1/recon/run`、`GET /api/v1/recon/report/{runId}` | P2-013 只读对账 |
| `api.recon.exportReport` | `GET /api/v1/recon/report/{runId}/export?format=csv`（文件流例外） | P3-031 对账面板「导出差异 CSV」 |

## 文件流例外（P3-031 对账 CSV 导出）

`api.recon.exportReport(runId)` 走 `requestBlob()`：2xx **不是**统一 JSON 信封，而是 `text/csv` 附件；
文件名取响应头 `Content-Disposition`（缺失回落 `recon-<runId>.csv`），页面用 `Blob + a[download]` 真实下载。
失败仍是统一 `ErrorResponse` → `ApiError`：403 `E-2001`（`details.requiredCapability=recon.report`）、
404 `E-3001`（runId 不存在）、409 `E-3002`（`details.reason=report_not_completed`，不导出半成品）。

## 约定（契约 v0.3.0）

- **统一信封**：成功取 `data` + 可选 `degraded`；失败取 `error/message/retryable/details/degraded`，统一抛 `ApiError`。
- **网络中断 / 超时**（默认 12s）→ 归一为 `E-4001`（上游超时），不在前端自造错误码。
- **降级不是错误**：`degraded` 出现时页面必须展示「已降级」条，并说明写操作是否暂停。
- **mock 标注**：`mock: true` 时页面必须标「演示数据（mock ERP）」，不得说成真实 ERP。
- **金额/工单号不自算自造**：接口失败时不展示金额、不展示工单号，给「已转人工」入口。

## 约束

- 页面不得直接 `fetch` 后端；统一走 `api`。
- 不得只改前端类型；契约变更必须同步 `docs/openapi`、`packages/shared`、Java DTO。
- 已知遗留：`apps/web` 仍未在 `package.json`/`tsconfig.json` 声明 `@property/shared` 依赖，
  因此 `contract.ts` 用相对路径引 shared 源码（改 tsconfig 需跨 write_scope，已在 P2 报告登记）。
