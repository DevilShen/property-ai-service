# services/ai-orchestrator · RAG 检索/生成编排层（W33：四约束生成 + 引用校验 + answerable）

> 任务：`task-76`（W30 PoC）/ `task-77`（W31 索引管道）/ `task-78`（W32 检索加固）/ **`task-79`（W33 生成层：四约束生成 + 引用硬校验 + answerable 终判 + `/internal/rag/answer`）**
> 设计方案：`docs/design/知识库RAG方案.md`（V1.1） · 计划：`docs/plan/RAG-进度计划.md`
> 边界：**只在 Python 编排层**；零 Java/前端/契约改动（`apps/**`、`docs/openapi/**`、`packages/shared/**` 未触碰）。
> 形态：CLI（ingest/reindex/selfcheck/poc/eval）+ **内网服务 `rag.service`（:4110，`X-Internal-Token` 鉴权）**：
> `POST /internal/rag/search`（W32）、`POST /internal/rag/answer`（W33）、`GET /internal/rag/health`。

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
| `rag/ingest.py` | CLI：Java KB + 演示语料 → 脱敏/切分 → embedding → Chroma（`--rebuild/--mock-embed/--limit/--no-corpus/--source-json`） |
| `rag/reindex.py` | CLI 增量：`--knowledge-id K-002` / `--remove` / `--expire-scan` / `--supersede V2.0` / `--history N` |
| `rag/selfcheck.py` | CLI 索引质量自检：条数/孤儿/过期残留/重复 sourceHash/空向量/维度/metadata/金标 gold；输出 JSON + 非零退出码 |
| `rag/poc.py` | CLI 金标评测：主指标 + **项目过滤前后对照** + **answerable 阈值扫描曲线** + `off/conditional/always/shadow` 重排统计 |
| `rag/service.py` | **内网服务（W32 检索 + W33 生成）**：标准库 `http.server`（:4110）、`POST /internal/rag/search`、`POST /internal/rag/answer`、`GET /internal/rag/health`（llm/embedding/vectorStore/reranker 组件状态 + promptVersion）、`X-Internal-Token`（未配置 → 401）、`--dry-run` |
| `rag/corpus/` | 演示语料（mock）81 篇：3 演示项目、多版本/过期/草稿/待复核/驳回样本、表格/代码块/问答对/≥2k 字长文 |
| `tests/` | `unittest`（**255 条**）：切分/脱敏/BM25/RRF（含跨进程 hash 种子确定性）/项目作用域/拒答守卫/重排控制器/检索服务（HTTP 401/200）/**生成层（四约束 Prompt、引用重试降级、版本意图、越权、超时/无 Key 降级）**/store/embedder/indexer/selfcheck |

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

## 5. 不入库物

`.data/`（Chroma 索引、embedding 缓存、manifest、**索引审计 JSONL**、日志、冒烟临时文件）已在
`services/ai-orchestrator/.gitignore` 忽略；参考工程 `.env` 的密钥**只读不复制**，
仓库内不保存任何真实 key/明文 PII；演示语料全部虚构（`demo: true`，加载期 PII 自检）。
