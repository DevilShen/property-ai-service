# services/ai-orchestrator · RAG 检索/生成/导入编排层（W33 生成 + task-85 知识导入）

> 任务：`task-76`（W30 PoC）/ `task-77`（W31 索引管道）/ `task-78`（W32 检索加固）/ **`task-79`（W33 生成层）** / **`task-85`（知识导入 P0/P1：preview/index/remove 内部端点）**
> 设计方案：`docs/design/知识库RAG方案.md`（V1.1） · `docs/design/知识导入方案.md`（V1.0） · 计划：`docs/plan/RAG-进度计划.md`
> 边界：**只在 Python 编排层**；零 Java/前端/契约改动（`apps/**`、`docs/openapi/**`、`packages/shared/**` 未触碰）。
> 形态：CLI（ingest/reindex/selfcheck/poc/eval）+ **内网服务 `rag.service`（:4110，`X-Internal-Token` 鉴权）**：
> `POST /internal/rag/search`（W32）、`POST /internal/rag/answer`（W33）、
> `POST /internal/rag/preview`、`POST /internal/rag/index`、`POST /internal/rag/remove`（task-85）、`GET /internal/rag/health`。

## 1. 目录

| 文件 | 职责 |
|---|---|
| `rag/config.py` | 配置：`--env-file` + 环境变量；参数对齐设计 §13；密钥只读不落库、日志掩码；W32 增 answerable 阈值/双低/覆盖率、重排 gap、重排缓存 TTL、服务端口 |
| `rag/llm.py` | 公司中转 OpenAI 兼容客户端：relay/mock/retrieval_only、推理型 token 识别、超时/重试、`temperature` 兼容回退 |
| `rag/embedder.py` | 智谱 `embedding-2`（1024 维，batch 16）+ LRU 内存缓存 + 磁盘缓存 + 重试/维度校验/失败降级 mock 并告警 |
| `rag/chunker.py` | 结构感知切分：标题层级、表格行组、代码块、问答对；500 / overlap 80 / min 30，保 `sectionPath`/`chunkSeq`/`sourceHash`/`blockType` |
| `rag/screener.py` | 手机号/身份证/邮箱脱敏、敏感词、去页眉页脚、Jaccard 0.9 去重 |
| `rag/corpus.py` | 演示语料加载：`rag/corpus/*.md`（front matter → 同构 knowledge item；强制 `demo:true`、加载期 PII 自检） |
| `rag/store.py` | 向量库抽象：`ChromaStore` / `PgVectorStore`（骨架，明确报错不回退）/ `MemoryStore`；metadata 含 `project_id/service_scope/archived/effective_from/to` |
| `rag/bm25.py` | 中文 char-bigram + 数字/英文词 BM25（k1=1.5，b=0.75；支持 project/status/category 过滤） |
| `rag/scope.py` | **W32 检索作用域**：`project_id IN (项目…, 'ALL')`、未传项目默认平台级 + 显式 WARNING、`UNSCOPED` 仅评测/排障、生效期/归档兜底 |
| `rag/hybrid.py` | 向量 top15 ∪ BM25 top15 → RRF(k=60)；**确定性排序键 `(-rrf, chunk_id)`**；强制项目作用域 + 生效期兜底 |
| `rag/guard.py` | **W32 拒答守卫**：`answerable/confidence/refusal_reason`（绝对阈值 + 双低分差 + hard-negative 覆盖率）+ 阈值扫描/阈值选择 |
| `rag/reranker.py` | LLM-as-rerank + **RerankController**（`off/conditional/always/shadow`）+ 结果缓存（`query_hash+project+ids_hash`，TTL 600s）+ 触发/超时/解析失败/缓存命中统计 |
| `rag/retriever.py` | `format_context`（`[n] 来源｜分类｜标题｜相似度`，≤6000 字）+ 纯向量基线（同样强制项目作用域） |
| `rag/generation.py` | **W33 生成层**：`PROMPT_VERSION`（w33-v1）+ 四约束/证据不可信/话术红线的 System Prompt；答案 `[n] ⊆ 上下文编号` 硬校验 + 追加提示重试 1 次 → 仍失败降级 `retrieval_only`；版本意图（`expired_trap`）`versionNotice`；越权/隐私门控；`answerable` 终判（guard + 生成结果 + 越权）；无 Key/mock/超时降级 |
| `rag/indexer.py` | 索引管道：切分 → 向量化 → 入库 → `expire_scan`（过期归档）→ 审计 JSONL；`reindex/remove/supersede/expire_scan` |
| `rag/importer.py` | **task-85 知识导入编排**：md/txt 直读、docx（python-docx：段落 + 表格→Markdown 行组 + 标题层级）、pdf（pypdf：逐页提取、空页告警、加密/扫描件可读报错）；归一化（全半角/空白/页码行/页眉页脚）；复用 `screener` 脱敏（`piiMaskedCount`）；复用 `chunker` 结构切分（seq/sectionPath/charCount）；去重（归一化文本 sha256 + `source_hash` 精确命中 + 可选 embedding 近似 ≥0.95）；安全限制（≤10MB / ≤200000 字符 / 解析超时 30s / 扩展名白名单）；`index_knowledge`（幂等 upsert）/`remove_knowledge` |
| `rag/ingest.py` | CLI：Java KB + 演示语料 → 脱敏/切分 → embedding → Chroma（`--rebuild/--mock-embed/--limit/--no-corpus/--source-json`） |
| `rag/reindex.py` | CLI 增量：`--knowledge-id K-002` / `--remove` / `--expire-scan` / `--supersede V2.0` / `--history N` |
| `rag/selfcheck.py` | CLI 索引质量自检：条数/孤儿/过期残留/重复 sourceHash/空向量/维度/metadata/金标 gold；输出 JSON + 非零退出码 |
| `rag/poc.py` | CLI 金标评测：主指标 + **项目过滤前后对照** + **answerable 阈值扫描曲线** + `off/conditional/always/shadow` 重排统计 |
| `rag/service.py` | **内网服务（W32 检索 + W33 生成 + task-85 导入）**：标准库 `http.server`（:4110）、`POST /internal/rag/search`、`POST /internal/rag/answer`、`POST /internal/rag/preview`、`POST /internal/rag/index`、`POST /internal/rag/remove`、`GET /internal/rag/health`（llm/embedding/vectorStore/reranker/**importer** 组件状态 + promptVersion）、`X-Internal-Token`（未配置 → 401）、`--dry-run`；导入端点请求体上限 16MB（base64 文件） |
| `rag/corpus/` | 演示语料（mock）81 篇：3 演示项目、多版本/过期/草稿/待复核/驳回样本、表格/代码块/问答对/≥2k 字长文 |
| `tests/` | `unittest`（**308 条**）：切分/脱敏/BM25/RRF（含跨进程 hash 种子确定性）/项目作用域/拒答守卫/重排控制器/检索服务（HTTP 401/200）/**生成层**/store/embedder/indexer/selfcheck/**知识导入（md/docx/pdf 解析、空/加密/损坏文件、超限、PII 计数、切分结构、去重命中、index 幂等、remove、preview/index/remove 端点）** |

金标集与结果：

| 路径 | 内容 |
|---|---|
| `tests/rag/build_golden.py` | 金标生成：v1（仅 KB 103 题）/ v2（KB + corpus，258 题） |
| `tests/rag/golden/knowledge-golden-v2.jsonl` | **v2 金标 258 题**（含口语化/多项目/过期陷阱/硬负样本/越权；`expect_not_ids`） |
| `tests/rag/results/rag-poc-w31.json` | W31 实跑结果（209 chunk + 真实 embedding + conditional 重排） |
| `tests/rag/results/rag-poc-w32.json` | **W32 实跑结果**（项目作用域前后对照 + 阈值扫描 + 重排统计 + 逐题明细） |
| `tests/rag/results/rag-answer-sample.json` | **W33 生成评测**：抽样 6 题真实 k3 生成 + LLM-as-judge faithfulness / 确定性 citation_precision + token/耗时 |
| `tests/rag/results/rag-answer-smoke.json` | W33 生成服务冒烟结果（24 项检查：鉴权/生成/引用/拒答/仅检索/版本意图/health/降级注入） |
| `tests/rag/smoke_service.py` | **检索服务冒烟**：起真实服务 → 401/200/项目过滤/拒答/health 全链路断言 |
| `tests/rag/smoke_answer.py` | **生成服务冒烟**：起真实服务 + 真实 k3 生成/拒答/仅检索/版本意图 + 引用越界/超时/无 Key 降级注入 |
| `tests/rag/eval_answer.py` | **生成评测脚本**：抽样真实 k3 生成 → citation_precision（确定性）+ faithfulness（LLM-as-judge） |
| `_dev/reports/RAG-W32-检索加固.md` | **W32 报告**（项目作用域效果、阈值扫描表、拒答率、RRF 证据、重排统计、延迟、遗留） |
| `_dev/reports/RAG-W33-生成与引用.md` | **W33 报告**（Prompt/引用校验/answerable 设计、冒烟、citation_precision/faithfulness、降级统计、遗留） |

## 2. 用法

```powershell
cd services/ai-orchestrator

# 1) 建索引（KB 44 条 + 演示语料 81 篇 = 209 chunk；真实 embedding + ChromaDB）
python -m rag.ingest --rebuild --env-file "<REF_RAG_PROJECT>\.env"

# 2) 索引质量自检（PASS → 退出码 0；结果写 .data/rag/selfcheck.json）
python -m rag.selfcheck

# 3) 金标评测（W32 主跑：强制项目作用域 + 重排关 + 阈值扫描；默认输出 rag-poc-w32.json）
python -m rag.poc --env-file "<REF_RAG_PROJECT>\.env" `
  --golden tests/rag/golden/knowledge-golden-v2.jsonl --rerank=off

# 3.1) 条件重排抽样（≤40 题；1.5s 预算口径 → 统计触发率/超时率）
python -m rag.poc --env-file "..." --golden tests/rag/golden/knowledge-golden-v2.jsonl `
  --rerank=conditional --rerank-limit 40 --rerank-alt=always --rerank-alt-limit 10

# 3.2) 看重排真实增益 + 缓存命中（放宽到 25s；同 run 内 repeat=2 打两次同一子集）
python -m rag.poc --env-file "..." --golden tests/rag/golden/knowledge-golden-v2.jsonl `
  --rerank=conditional --rerank-limit 10 --rerank-repeat 2 --skip-ablation `
  --rerank-timeout-ms 25000 --rerank-max-tokens 1024 --out ../../tests/rag/results/rag-poc-w32-rerank.json

# 4) 内部服务（:4110；token 只从环境变量读，未配置 → 全部 401）
$env:RAG_INTERNAL_TOKEN = "<内网 token>"
python -m rag.service --env-file "..." --port 4110
python -m rag.service --dry-run          # 只打印配置（密钥掩码）+ 索引/模型/重排模式/promptVersion
python tests/rag/smoke_service.py        # 检索冒烟：401/200/项目过滤/拒答/health
python tests/rag/smoke_answer.py         # 生成冒烟：真实 k3 生成/引用/拒答/仅检索/版本意图/降级注入
python tests/rag/eval_answer.py --sample 6 --judge 4   # 生成评测：citation_precision + faithfulness（LLM-as-judge）

# 5) 增量索引 / 下架 / 过期扫描 / 版本替代（审计 .data/rag/index-audit.jsonl）
python -m rag.reindex --knowledge-id K-002
python -m rag.reindex --knowledge-id K-002 --remove
python -m rag.reindex --expire-scan

# 6) 离线可跑（无 Key）：mock embedding + mock 重排透传 + 内存/临时 Chroma
python -m rag.poc --mock-embed --limit 20 --out ../../tests/rag/results/rag-poc-mock.json

# 7) 单测（仓库根目录或本目录均可）
python -m unittest discover -s tests -t .

# 8) 知识导入冒烟（task-85）：起临时编排层 :4111（mock embedding + 内存向量库 + 临时目录）
python tests/rag/smoke_import.py
```

## 3. 实测环境约束（2026-11-16，详见报告）

1. **中转站 UA 白名单**：`deepseek-v4-flash` / `deepseek-v4-pro` 无 UA → **403 `agent_not_allowed`**
   （需公司 IT 授权）。本模块**不伪装 UA**（伪装属绕过访问控制，合规禁止），PoC 用 `k3`。
2. **推理型 token 预算**：`k3` / `kimi-for-coding` 的 `reasoning_tokens` 计入 `max_tokens`；
   重排给 128/512 时 `finish_reason=length` 且正文为空 → 代码识别为可读错误并原序透传，
   实测需 **1024**。
3. **temperature 限制**：上述模型只接受 `temperature=1`（传 0 → 400）；客户端自动去掉
   `temperature` 重试并标 `temperature_fallback=True`（无法真正做到温度 0，报告已列风险）。
4. **重排延迟**：15 候选实测 **P50 ≈ 9s、P95 ≈ 20s**，远超设计 1.5s 预算 → 1.5s 预算下
   **W32 实测触发 25 题、25 题全部超时（100%）** → 默认 `--rerank=off`；条件触发只用于
   可接受延迟的场景（需 flash 模型授权后才有意义）。
5. **Java 分页**：`GET /api/v1/knowledge?pageSize=` 上限 **100**（200 → 400 `E-1001`），ingest 自动分页。
6. **生成延迟（W33 实测）**：k3 推理型单题生成 20—45s（reasoning 268—702 tok，`max_tokens=4096` 足够）；
   任务卡「首答超时 20s」下 k3 约半数首答被截断 → 实现为**首答 20s + 重试预算 45s**
   （`RAG_ANSWER_RETRY_TIMEOUT_MS`，含引用校验纠正重试）。`deepseek-v4-flash` 授权后可置 0 恢复 20s 单次预算。
   W33 冒烟实测：两题真实生成均在重试预算内成功（单题 ≈48s，含一次 20s 超时），成本约 2 次调用/题（如实记录）。

## 4. 降级矩阵

| 故障 | 行为 |
|---|---|
| 无 LLM Key / `LLM_PROVIDER=retrieval_only` | `mode=retrieval_only`（片段 + `[n]`），不报错 |
| 重排失败/超时/正文空/输出无编号 | 原序透传（`status=timeout/parse_error/error`），不阻塞问答，计入 `timeout_rate`/`parse_error_rate` |
| 重排「未触发」（条件模式高置信题） | 原序透传并记 `status=skipped_high_conf`（省时省钱，不视为降级） |
| 重排缓存命中 | 直接复用名次（`status=cache_hit`，TTL 600s，key=提问+项目+候选集） |
| `--rerank=shadow` | 照常计算但不生效（返回融合序 + 记录 `shadow_ids`），用于离线评估 |
| BM25 索引未构建 | 首次检索懒构建（千级语料 <1s） |
| ChromaDB 不可用 | 内存向量库回退（`backend=memory`，报告/health 如实标注） |
| `VECTOR_STORE=pgvector` 但无驱动/DSN | **明确抛 `PgVectorUnavailable`**（可读错误，不静默回退） |
| Embedding 传输失败（重试耗尽） | 降级 mock 向量继续索引 + 告警（`degraded=true`） |
| 向量相似度 < 召回阈值 | 空召回 → `answerable=false`（`refusal_reason=no_evidence/low_confidence`） |
| **生成**：无 Key / mock / `provider=retrieval_only` | `mode=retrieval_only`（片段 + `[n]`）+ `degraded=true`，不报错 |
| **生成**：LLM 超时/限流/403/推理型空正文 | 重试 1 次（首答 20s → 重试 45s 预算）仍失败 → `retrieval_only` + `degraded=true` + `error` 可读原因 |
| **生成**：答案无 `[n]`/引用越界 | 追加提示重试 1 次；仍失败 → `retrieval_only` + `answerable=false` + `refusalReason=citation_invalid` |
| **生成**：话术红线（承诺减免/赔付）两次命中 | 丢弃模型正文 → `retrieval_only`（合规优先，`detail.rejected_answer`） |
| **生成**：答案 >400 字 / 缺版本提示 | 确定性压缩至 ≤400 字 / 追加「该口径已更新，以现行版本为准」（`versionNotice=true`） |
| **生成**：越权/隐私问题（他人手机号/名单/删记录/跨项目） | `answerable=false` + `refusalReason=out_of_scope`，不调模型、不返回来源 |
| 知识过期（`effectiveTo < today`） | `expire_scan` → `status=expired` + `archived=true`；召回层再加**生效期兜底** |
| 未传项目作用域 | 默认平台级 `ALL` + **显式 WARNING 日志**（不静默全库召回） |
| 检索服务未配置 token | **所有请求 401 `token_not_configured`**（fail-closed，避免检索服务裸奔） |
| **导入**：`python-docx` / `pypdf` 缺失 | health `importer.docx/pdf=false`（`available` 仍为 true：粘贴/md/txt 可用）；对应格式上传 → 422 `parse_failed`（`reason=*_dependency_missing`） |
| **导入**：近似去重（embedding/向量查询）失败 | 只告警 `dedupe_approx_skipped`，不阻断预览（精确去重不受影响） |
| **导入**：索引库不可读（去重失败） | 只告警 `dedupe_skipped`，预览照常返回 |

## 5. 不入库物

`.data/`（Chroma 索引、embedding 缓存、manifest、**索引审计 JSONL**、日志、冒烟临时文件）已在
`services/ai-orchestrator/.gitignore` 忽略；参考工程 `.env` 的密钥**只读不复制**，
仓库内不保存任何真实 key/明文 PII；演示语料全部虚构（`demo: true`，加载期 PII 自检）。

## 6. 知识导入内部协议（task-85，P0/P1）

> 设计依据：`docs/design/知识导入方案.md` §3/§4/§5.2。**只做内部协议（`/internal/rag/*`），不改 `docs/openapi`、
> 不改 Java 契约**；Java BFF 侧（`/api/v1/knowledge/imports*`）由契约任务另行落地，字段对齐见下。

### 6.1 端点

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/internal/rag/preview` | 解析 + 归一化 + PII 脱敏 + 结构切分 + 去重提示（**不写库**） |
| `POST` | `/internal/rag/index` | 单知识 upsert（写 Chroma/内存库 + 元数据；幂等） |
| `POST` | `/internal/rag/remove` | 撤销（删除该 `knowledgeId` 全部 chunk + 审计） |
| `GET` | `/internal/rag/health` | 新增 `importer: {available, docx, pdf}` 组件（其余字段不变） |

鉴权同既有口径：`X-Internal-Token`（只读 `RAG_INTERNAL_TOKEN`，未配置 → 全部 401）；
响应信封 `{ok, data}` / `{ok, error: {code, message, reason?}}`；keep-alive/日志不落正文的口径不变。

### 6.2 `POST /internal/rag/preview`

请求：

```jsonc
{
  "fileName": "物业费说明.md",       // 可选：标题建议用（去扩展名）；扩展名必须白名单
  "fileType": "md",                  // 必填：txt | md | docx | pdf（markdown → md）
  "text": "……",                     // txt/md：直接粘贴文本（E1）
  "contentBase64": "UEsDBA…",        // docx/pdf（及 txt/md 文件上传）：base64；≤10MB
  "projectId": "P-JZY",              // 必填（不参与解析，供 Java 侧核对/日志）
  "title": "可选标题"                 // 可选：优先作为 suggestedTitle
}
```

响应 `data`：

```jsonc
{
  "suggestedTitle": "物业费说明",      // 显式 title > 文件名去扩展名 > 正文首个 heading > 未命名知识-<日期>
  "charCount": 106,                   // 归一化后正文字符数（≤200000，超限 413）
  "piiMaskedCount": 2,                // 手机号+身份证+邮箱命中并已掩码的总数
  "chunks": [{"seq": 1, "sectionPath": "物业费说明 > 计费口径", "text": "……", "charCount": 120}],
  "totalChunks": 1,
  "warnings": [{"code": "pii_masked", "message": "已自动脱敏 2 处个人信息（手机号/身份证/邮箱）"}],
  "duplicates": [{"knowledgeId": "K-OLD", "title": "旧版说明", "similarity": 1.0}],
  "contentMasked": "……"               // 归一化+脱敏后的全文（Java 存档/commit 用；≤200000 字符，超出截断并告警）
}
```

`chunks[].seq` **从 1 起连续且稳定**（对齐 `docs/openapi` 的 `KnowledgeImportPreviewChunk` / `commit.chunks` 勾选口径）。

`warnings[].code`：契约已知码 `pii_masked` / `duplicate_suspected` / `truncated`；实现附加诊断码
`page_number_removed`、`header_footer_removed`、`pdf_empty_page`、`docx_tables_extracted`、
`text_encoding_fallback`、`dedupe_skipped`、`dedupe_approx_skipped`（契约规定「未知 code 前端按通用警告展示」）。

错误映射（HTTP 状态 ↔ `error.code` ↔ `error.reason`）：

| 状态 | `code` | 典型 `reason` |
|---|---|---|
| 400 | `bad_request` | `unsupported_extension` / `unsupported_file_type` / `file_type_mismatch` / `missing_file_type` / `invalid_base64` / `missing_content` / `binary_requires_base64` / `binary_content` |
| 413 | `payload_too_large` | `file_too_large`（>10MB）/ `content_too_large`（>200000 字符）/ `body_too_large`（请求体 >16MB） |
| 422 | `parse_failed` | `pdf_encrypted` / `pdf_empty_text` / `pdf_bad_magic` / `pdf_parse_error` / `docx_bad_magic` / `docx_parse_error` / `docx_empty_text` / `empty_text` / `empty_chunks` / `parse_timeout` |
| 500 | `E-INTERNAL` | 服务内部错误（不泄漏堆栈） |

### 6.3 `POST /internal/rag/index`

```jsonc
{
  "knowledgeId": "K-IMP-001", "title": "物业费说明", "projectId": "P-JZY",
  "category": "fee", "owner": "王管家", "version": "V1.0",
  "effectiveFrom": "2026-01-01", "effectiveTo": "",
  "status": "published",             // 缺省 published（召回只取 published，未审内容不得进召回）
  "content": "……",                   // 通常是 preview.contentMasked（≤200000 字符）
  "sourceDocId": "DOC-001",          // 可选：来源文档 ID（元数据 source_doc_id）
  "checksum": "sha256-…"             // 幂等键；缺省按 content sha256 计算
}
```

响应：`{"ok": true, "data": {"knowledgeId": "K-IMP-001", "chunks": 3, "indexedAt": "2026-11-17T22:45:19+08:00", "skipped": false}}`

幂等：同 `knowledgeId + checksum` 已存在 → `skipped: true`（不重复写、`chunks` 为库内现有块数）；
checksum 变化 → 先删旧 chunk 再写入（`chunk_id = {knowledgeId}#{seq}` 稳定）。
写入元数据追加：`doc_checksum` / `content_sha256`（归一化脱敏文本 sha256，供 preview 精确去重）/
`source_doc_id` / `indexed_at`；失败返回 400/413/422，**不产生半成品索引**。

### 6.4 `POST /internal/rag/remove`

```jsonc
{"knowledgeId": "K-IMP-001"}
→ {"ok": true, "data": {"removed": 3}}
```

复用 `indexer.remove_knowledge`（写 `.data/rag/index-audit.jsonl` 审计），并立即失效检索器 BM25 缓存
（否则撤销后仍可能被 BM25 召回）。

### 6.5 去重口径

1. **精确（默认，无 embedding 成本）**：
   - 知识级：`content_sha256`/`doc_checksum` 与本次归一化脱敏文本 sha256 相等 → `similarity=1.0`；
   - chunk 级：对 `store.all_chunks()` 的 `source_hash` 做命中（含 `{title}：{chunkText}` 前缀变体，
     因为入库 chunk 正文带标题前缀）→ `similarity=1.0`。
2. **近似（可选）**：新文本首块 embedding → 向量库 top5，余弦 ≥0.95 → `similarity=0.95…`；
   embedding/查询失败只加 `dedupe_approx_skipped` 告警，不阻断预览。
3. `duplicates` 按 `knowledgeId` 聚合、最多 5 条；Java 侧默认提示跳过，可经「强制新建」覆盖。

### 6.6 依赖与安全

- `python-docx`（docx）、`pypdf`（pdf）为**格式解析依赖**，缺失时 health `importer.docx/pdf=false`、
  对应上传 422 `*_dependency_missing`；粘贴文本与 md/txt 不依赖它们。
- 安全限制：单文件 ≤10MB、正文字符 ≤200000、解析超时 30s（`ThreadPoolExecutor` 超时即 422）、
  扩展名白名单`txt/md/markdown/docx/pdf`（其余 400）；txt 含 NUL 视为二进制拒绝；
  docx/pdf 先校验魔数（`PK` / `%PDF-`）。
- 隐私：预览/索引均先 `screener` 掩码（手机号/身份证/邮箱）；日志只记文件类型/字符数/块数/PII 计数/重复数。
- 冒烟：`python tests/rag/smoke_import.py`（起 :4111，mock embedding + 内存库 + 临时目录；24 项检查）。
