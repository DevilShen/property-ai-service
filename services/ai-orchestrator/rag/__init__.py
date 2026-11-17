"""物业 AI 智能客服 · RAG 检索/生成编排层（W33：四约束生成 + 引用校验 + answerable）

模块边界（AGENTS.md 硬约束）：模型/向量调用只在本 Python 编排层，Java BFF 负责
鉴权/隔离/审计。本包为 **PoC**：W32 起提供**内网检索服务** `rag.service`
（`POST /internal/rag/search` / `GET /internal/rag/health`，:4110，`X-Internal-Token` 鉴权），
W33 增 `POST /internal/rag/answer`（生成 + 引用校验 + answerable 终判），
仍不改动 `apps/**`、`docs/openapi/**`、`packages/shared/**`。

目录（W30 → W31 → W32 → W33）：
    config.py      配置（环境变量 / --env-file；密钥只读不落库，日志掩码；
                   W32 新增 answerable 阈值 / 重排 gap / 重排缓存 / 服务端口；
                   W33 新增生成 timeout/retry/长度上限，llm_max_tokens 默认 4096）
    llm.py         公司中转 OpenAI 兼容客户端（relay / mock / retrieval_only）
    embedder.py    智谱 embedding-2（1024 维，batch 16）+ LRU + 磁盘缓存 + 重试/降级
    chunker.py     结构感知中文切分（标题层级/条款/表格行组/代码块/问答对）
    screener.py    脱敏 + 去页眉页脚 + Jaccard 去重
    corpus.py      演示语料加载（rag/corpus/*.md，虚构 mock）
    store.py       向量库抽象（Chroma / PgVector 骨架 / Memory）+ metadata(project_id/生效期)
    bm25.py        中文 char-bigram + 数字/英文词 BM25（支持项目/状态/分类过滤）
    scope.py       **W32 检索作用域**：项目隔离口径 `project_id IN (项目…, 'ALL')`、
                   未传项目默认平台级 + 显式 WARNING、生效期/归档兜底
    hybrid.py      向量 ∪ BM25 → RRF(k=60)，**确定性排序键 (-rrf, chunk_id)** + 项目作用域
    guard.py       **W32 拒答守卫**：answerable / confidence / refusal_reason
                   （绝对阈值 + 双低分差 + hard-negative 覆盖率）+ 阈值扫描/选择
    reranker.py    LLM-as-rerank + **RerankController**（off/conditional/always/shadow）
                   + 结果缓存（query_hash+project+ids_hash，TTL 600s）+ 降级统计
    retriever.py   format_context（[n] 来源/分类/标题/相似度，≤6000 字）+ 强制项目作用域
    generation.py  **W33 生成层**：PROMPT_VERSION（w33-v1）四约束 Prompt + 证据不可信 +
                   引用硬校验（[n] ⊆ 上下文）+ 重试/降级 retrieval_only + 版本意图 versionNotice +
                   越权门控 + answerable 终判（无 Key/mock/超时 → 仅检索）
    indexer.py     索引管道：切分→向量化→入库→`expire_scan`（过期归档）+ 审计 JSONL
    ingest.py      CLI：KB + corpus → 筛/切/脱敏 → embedding → Chroma
    reindex.py     CLI：增量 reindex / remove / expire-scan / supersede
    selfcheck.py   CLI：索引质量自检（条数/孤儿/过期残留/重复 hash/空向量/维度）
    poc.py         CLI：金标评测（Hit@5 / MRR / 拒答 / 项目作用域前后对照 / 阈值扫描 /
                   off|conditional|always|shadow 重排统计）
    service.py     **内网服务**（标准库 http.server；token 鉴权；--dry-run）：
                   search（W32）+ answer（W33）+ health（W33 组件状态）

W32 口径说明：
    - 数据源 = Java KB 44 条（平台级 `project_id=ALL`）+ `rag/corpus/` 81 篇演示语料
      （3 演示项目 P-YQY/P-JZY/P-LVY，虚构 mock）→ 合并索引 **209 chunk**；
    - 召回强制项目作用域（`project_id IN (项目…, 'ALL')`）+ `status=published` + 生效期兜底；
    - answerable 上移到检索层：无证据 / top1 < 阈值 / 双低 / hard-negative 覆盖率不足 → 拒答；
    - 中转站 `deepseek-v4-flash` / `deepseek-v4-pro` 无 UA → 403 `agent_not_allowed`
      （需公司 IT 授权），PoC 重排/生成仍用 `k3`（推理型：重排 1.5s 预算 100% 超时 → 默认 off；
      生成单题 20—45s → 首答 20s + 重试 45s 预算，flash 授权后收紧）。

合规：语料全部虚构（无真实手机号/身份证/姓名）；密钥只从 `--env-file` 只读取，
日志/JSON 仅 `*** (len=N)`；不伪装 UA。
"""

__all__ = ["config"]
