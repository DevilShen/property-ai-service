"""配置模块（RAG PoC）
=====================

职责：集中所有可调参数，参数名与设计文档 `docs/design/知识库RAG方案.md` §13 对齐。

读取优先级（高 → 低）：
    1. 显式传入的 overrides（CLI 参数）
    2. 进程环境变量 `RAG_<NAME>`；部分键回退到参考工程的同名键（见 `_PLAIN_FALLBACK`）
    3. 代码默认值（与设计 §13 一致）

安全约束（AGENTS.md / 设计 §9）：
    - 密钥只从环境变量或 `--env-file` 指向的**参考工程 .env** 读取，绝不写入仓库；
    - `safe_dict()` / `mask_secret()` 供日志与报告使用，日志中密钥只出现掩码；
    - `RAG_LLM_USER_AGENT` 默认空：**不伪装 UA**（伪装绕过公司访问控制，属违规）。
"""

from __future__ import annotations

import os
from dataclasses import dataclass, fields, replace
from pathlib import Path
from typing import Any

# services/ai-orchestrator/rag/config.py -> services/ai-orchestrator
PACKAGE_DIR = Path(__file__).resolve().parent
SERVICE_DIR = PACKAGE_DIR.parent
REPO_DIR = SERVICE_DIR.parent.parent
DEFAULT_DATA_DIR = SERVICE_DIR / ".data" / "rag"
DEFAULT_CHROMA_DIR = DEFAULT_DATA_DIR / "chroma"

PREFIX = "RAG_"

# 这些键**只**从 RAG_ 前缀读取，避免参考工程 .env 的同名值把 PoC 带偏：
#   - LLM_MODEL：参考工程用 deepseek-v4-flash（本仓库无 UA → 403），PoC 默认 k3
#   - LLM_USER_AGENT：参考工程用伪装 UA（合规禁止，不复制）
#   - LLM_PROVIDER / RERANK_MODEL：参考工程语义不同
#   - CHROMA_DIR / CHROMA_COLLECTION：参考工程指向它自己的 chroma_db
_NO_PLAIN_FALLBACK = frozenset(
    {
        "LLM_MODEL",
        "LLM_USER_AGENT",
        "LLM_PROVIDER",
        "RERANK_MODEL",
        "CHROMA_DIR",
        "CHROMA_COLLECTION",
        "DATA_DIR",
        # W32 新增键：参考工程 .env 里可能有同义但语义不同的值，只从 RAG_ 前缀读
        "ANSWER_THRESHOLD",
        "ANSWER_MIN_GAP",
        "ANSWER_COVERAGE_MIN",
        "RERANK_LOW_CONF_GAP",
        "RERANK_CACHE_TTL",
        "SERVICE_PORT",
        "SERVICE_HOST",
        "INTERNAL_TOKEN",
        # W33：参考工程 .env 用 16384（其重排/生成混用），本仓库生成口径固定 4096（设计 §13），
        # 只允许 RAG_LLM_MAX_TOKENS 显式覆盖，避免只读 .env 把 token 预算带偏。
        "LLM_MAX_TOKENS",
        "ANSWER_TIMEOUT_MS",
        "ANSWER_RETRY_TIMEOUT_MS",
        "ANSWER_RETRIES",
        "ANSWER_MAX_CHARS",
    }
)

# 演示语料目录（W31：虚构 Markdown 语料，与 Java 知识库合并索引）
DEFAULT_CORPUS_DIR = PACKAGE_DIR / "corpus"

# 分类 → 中文展示名（format_context / 报告用）
CATEGORY_NAMES: dict[str, str] = {
    "fee": "费用",
    "utility": "能耗/公摊",
    "parking": "停车",
    "renovation": "装修",
    "repair": "报修",
    "notice": "通知",
    "pet": "宠物",
    "noise": "噪音",
    "visitor": "访客",
    "complaint": "投诉",
    "other": "其他",
}


def category_name(category: str) -> str:
    """分类英文码 → 中文名；未知分类原样返回。"""
    return CATEGORY_NAMES.get((category or "").strip(), category or "")


# 演示 3 个项目（与 apps/server-java ProjectController / shared DEMO_PROJECTS 对齐：
# 仅为**演示数据**，不是真实组织架构；参见 AGENTS.md NEVER #11）
PROJECT_NAMES: dict[str, str] = {
    "P-YQY": "云栖苑",
    "P-JZY": "金竹苑",
    "P-LVY": "绿苑",
}
# 平台级知识（Java 知识库 44 条无 projectId）→ 全项目可见
GLOBAL_PROJECT_ID = "ALL"


def project_name(project_id: str) -> str:
    """项目 ID → 中文名；未知项目原样返回。"""
    key = (project_id or "").strip()
    return PROJECT_NAMES.get(key, key)


def global_project_id() -> str:
    """平台级项目标识（无 projectId 的知识归此，全项目可见）。"""
    return GLOBAL_PROJECT_ID


def mask_secret(value: str | None) -> str:
    """密钥掩码：**只输出长度**，不保留任何明文字符（日志/报告可安全引用）。"""
    if not value:
        return ""
    return f"*** (len={len(value)})"


def normalize_base_url(url: str, require_v1: bool = False) -> str:
    """规范化 OpenAI 兼容 base_url。

    输入：
        url        : 原始 base_url（如 https://relay.example.com/v1）
        require_v1 : 是否强制以 /v1 结尾（LLM 中转站要求；Embedding 智谱为 /v4，不强制）
    输出：规范化后的 base_url；为空时返回空串。
    """
    url = (url or "").strip()
    if not url:
        return ""
    url = url.rstrip("/")
    if require_v1 and not url.endswith("/v1"):
        url = url + "/v1"
    return url


def load_env_file(path: str | os.PathLike[str] | None, override: bool = False) -> bool:
    """读取 `--env-file`（默认用 python-dotenv；未安装时手工解析）。

    输入：
        path     : .env 路径；None/空 → 不读
        override : 是否覆盖已有进程环境变量（默认 False，进程环境优先）
    输出：bool，是否读取成功（文件不存在返回 False，不抛异常）。

    只读该文件，**不复制/打印/入库**其中任何密钥。
    """
    if not path:
        return False
    if not Path(path).is_file():
        return False
    try:
        from dotenv import load_dotenv  # 已安装；缺失时走手写解析

        load_dotenv(str(path), override=override)
        return True
    except Exception:  # pragma: no cover - dotenv 缺失/异常时的手工回退
        text = Path(path).read_text(encoding="utf-8", errors="ignore")
        for raw in text.splitlines():
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            key = key.strip()
            value = value.strip().strip('"').strip("'")
            if not key:
                continue
            if override or key not in os.environ:
                os.environ[key] = value
        return True


def _env(name: str, default: str = "") -> str:
    """按优先级读取配置项：RAG_<NAME> → <NAME>（白名单外不回退）。"""
    value = os.environ.get(PREFIX + name)
    if value is not None and value != "":
        return value
    if name not in _NO_PLAIN_FALLBACK:
        plain = os.environ.get(name)
        if plain is not None and plain != "":
            return plain
    return default


def _env_int(name: str, default: int) -> int:
    try:
        return int(_env(name, str(default)).strip())
    except (TypeError, ValueError):
        return default


def _env_float(name: str, default: float) -> float:
    try:
        return float(_env(name, str(default)).strip())
    except (TypeError, ValueError):
        return default


def _env_bool(name: str, default: bool) -> bool:
    raw = _env(name, "true" if default else "false").strip().lower()
    return raw in {"1", "true", "yes", "on", "y"}


def _parse_list(raw: str) -> list[str]:
    """逗号/分号/空白分隔 → 去重列表（敏感词表用）。"""
    for sep in (";", "，", "、", "|"):
        raw = raw.replace(sep, ",")
    return [x.strip() for x in raw.split(",") if x.strip()]


@dataclass(frozen=True)
class Settings:
    """RAG 全量配置（不可变；用 `with_overrides` 派生新配置）。"""

    # ---- 公司中转 LLM（设计 §13）----
    llm_provider: str = "relay"  # relay | mock | retrieval_only
    llm_base_url: str = "https://relay.example.com/v1"
    llm_model: str = "k3"  # PoC：deepseek-v4-flash 无 UA 被 403，改用 k3
    llm_api_key: str = ""
    llm_temperature: float = 0.2  # 问答偏严谨；模型不支持（如 k3 只收 temperature=1/省略）→ 客户端 fallback 并记录
    llm_max_tokens: int = 4096  # 设计 §13：推理型模型 reasoning 计入 max_tokens，给小会 finish_reason=length 且正文为空
    llm_user_agent: str = ""  # 默认空 = 不伪装 UA（合规）
    context_max_chars: int = 6000

    # ---- Embedding（智谱 embedding-2 / mock）----
    embed_provider: str = "zhipu"  # zhipu | mock
    embed_base_url: str = "https://open.bigmodel.cn/api/paas/v4"
    embed_model: str = "embedding-2"
    embed_api_key: str = ""
    embed_dim: int = 1024
    embed_batch_size: int = 16

    # ---- 切分 / 脱敏 / 去重 ----
    chunk_size: int = 500
    chunk_overlap: int = 80
    chunk_min_length: int = 30
    dedup_similarity: float = 0.9
    mask_token: str = "***"
    mask_amount: bool = False
    sensitive_words: tuple[str, ...] = ()

    # ---- 检索 / 融合 ----
    retriever_mode: str = "hybrid"  # hybrid | vector
    top_k: int = 5
    recall_per_route: int = 15
    rrf_k: int = 60
    max_per_knowledge: int = 3
    score_threshold: float = 0.3
    score_threshold_mock: float = 0.02
    recall_status: tuple[str, ...] = ("published",)  # 召回只取生效发布态（生产口径）

    # ---- LLM-as-rerank（设计 §5）----
    rerank_enabled: bool = True
    rerank_candidates: int = 15
    rerank_top_n: int = 5
    rerank_model: str = ""  # 空 = 复用 llm_model
    rerank_temperature: float = 0.0
    rerank_timeout_ms: int = 1500
    # 设计口径 128；实测 k3/kimi 属推理模型，128/512 全被 reasoning 吃光 → 正文空。
    # 默认仍保持 128（暴露该约束），PoC 实跑用 --rerank-max-tokens 1024 覆盖。
    rerank_max_tokens: int = 128
    rerank_max_chars_per_doc: int = 200

    # ---- 向量库 ----
    vector_store: str = "chroma"  # chroma | pgvector | memory
    data_dir: Path = DEFAULT_DATA_DIR
    chroma_dir: Path = DEFAULT_CHROMA_DIR
    chroma_collection: str = "property_kb"
    # pgvector 连接（W31 仅接口骨架；驱动/连接不可用 → 明确抛错，不静默回退）
    pg_dsn: str = ""
    pg_table: str = "knowledge_chunks"

    # ---- 语料 / 项目作用域（W31）----
    corpus_dir: Path = DEFAULT_CORPUS_DIR
    corpus_enabled: bool = True
    # 平台级知识（Java KB 44 条无 projectId）→ 全项目可见；corpus 文档带具体项目
    global_project_id: str = "ALL"
    default_project_id: str = ""  # 空 = 不按项目过滤（平台级视角）
    recall_projects: tuple[str, ...] = ()  # 非空 → 召回只取这些项目 + 平台级

    # ---- Embedding 升级（W31）----
    embed_cache_size: int = 4096  # LRU 内存条目上限（淘汰只影响进程内命中率，磁盘缓存保留）
    embed_retries: int = 2  # 失败重试次数（指数退避 0.5s/1s）
    embed_fallback_mock: bool = True  # 真实 embedding 失败 → 降级 mock 并告警（不中途中止索引）
    embed_max_consecutive_failures: int = 3  # 连续失败批次达到该值 → 触发降级

    # ---- 索引失效 / 审计（W31）----
    expire_status: str = "expired"  # expire_scan 把过期条目的 status 改为该值（保留归档，不删）
    archived_flag_meta: str = "archived"
    audit_path: Path = DEFAULT_DATA_DIR / "index-audit.jsonl"

    # ---- 重排触发模式（W31；W32 扩 shadow + 默认 off）----
    rerank_mode: str = "off"  # off | conditional | always | shadow（默认 off，按需显式开启）
    rerank_trigger_top1: float = 0.0  # conditional 附加规则：top1 相似度低于该值触发（0 = 关闭该规则）
    rerank_trigger_gap: float = 0.05  # conditional：top1-top2 差值小于该值 → 触发重排（W32 主规则）

    # ---- 条件重排策略 / 缓存（W32）----
    rerank_low_conf_gap: float = 0.05  # RAG_RERANK_LOW_CONF_GAP：候选≥2 且 top1-top2 小于该值 → 触发
    rerank_cache_ttl_s: int = 600  # 重排结果缓存 TTL（key=query_hash+project_id+ids_hash）
    rerank_cache_size: int = 512  # 缓存条目上限（LRU 淘汰）

    # ---- 拒答 / answerable（W32；默认值由 258 题阈值扫描 + hard-negative 特征网格确定，见报告）----
    answer_threshold: float = 0.44  # RAG_ANSWER_THRESHOLD：top1 < 该值 → low_confidence
    answer_min_gap: float = 0.03  # RAG_ANSWER_MIN_GAP：「双低」分差下限
    answer_flat_margin: float = 0.04  # RAG_ANSWER_FLAT_MARGIN：「双低」绝对分窗口（阈值+0.04 内）
    answer_gap_rule: bool = True  # RAG_ANSWER_GAP_RULE：是否启用「双低」规则
    answer_coverage_min: float = 0.10  # RAG_ANSWER_COVERAGE_MIN：hard-negative 覆盖率下限
    answer_coverage_rule: bool = True  # RAG_ANSWER_COVERAGE_RULE：是否启用 hard-negative 覆盖率规则

    # ---- 生成层 answer（W33；任务 RAG-021/022/023）----
    answer_timeout_ms: int = 20000  # RAG_ANSWER_TIMEOUT_MS：单次生成超时（任务卡 20s；flash 非推理模型可满足）
    answer_retry_timeout_ms: int = 45000  # RAG_ANSWER_RETRY_TIMEOUT_MS：仅超时重试时放宽的预算（k3 推理型 25—35s；0=不放宽）
    answer_retries: int = 1  # RAG_ANSWER_RETRIES：重试次数（超时/瞬时错误；重试由生成层统一控制）
    answer_max_chars: int = 400  # RAG_ANSWER_MAX_CHARS：中文答案长度上限（Prompt 约束 + 超限压缩一次）

    # ---- 内部检索服务（W32；services/ai-orchestrator/rag/service.py）----
    service_host: str = "127.0.0.1"
    service_port: int = 4110
    service_token_env: str = "RAG_INTERNAL_TOKEN"  # 只读该环境变量；未配置 → 一律 401
    service_max_top_k: int = 20

    # ---- 取数（Java :4000 演示 JWT）----
    knowledge_api: str = "http://localhost:4000"
    knowledge_page_size: int = 100  # 实测 pageSize 上限 100（200 → E-1001）
    knowledge_account: str = "it-01"
    knowledge_password: str = "demo123"

    # ---- 评测 ----
    golden_path: Path = REPO_DIR / "tests" / "rag" / "golden" / "knowledge-golden.jsonl"
    golden_v2_path: Path = REPO_DIR / "tests" / "rag" / "golden" / "knowledge-golden-v2.jsonl"
    results_path: Path = REPO_DIR / "tests" / "rag" / "results" / "rag-poc.json"
    results_w31_path: Path = REPO_DIR / "tests" / "rag" / "results" / "rag-poc-w31.json"
    results_w32_path: Path = REPO_DIR / "tests" / "rag" / "results" / "rag-poc-w32.json"

    # ---- 派生属性 ----
    @property
    def is_mock_embed(self) -> bool:
        """Embedding 是否 mock（离线哈希向量）。"""
        return (self.embed_provider or "").strip().lower() == "mock"

    @property
    def is_chroma(self) -> bool:
        """向量库是否为 ChromaDB（否则内存回退）。"""
        return (self.vector_store or "").strip().lower() == "chroma"

    @property
    def is_pgvector(self) -> bool:
        """向量库是否配置为 pgvector（W31 仅接口骨架，需驱动 + 可达 PG）。"""
        return (self.vector_store or "").strip().lower() in {"pg", "pgvector", "postgres"}

    @property
    def effective_rerank_mode(self) -> str:
        """重排模式规范化：off | conditional | always | shadow（未知值按 off）。

        W32 起默认 **off**（设计 §5「默认不重排，低置信才触发」）；`shadow` = 只算不生效
        （记录 shadow 排序用于离线对比，返回仍是融合序）。
        """
        mode = (self.rerank_mode or "").strip().lower()
        return mode if mode in {"off", "always", "conditional", "shadow"} else "off"

    @property
    def project_scope(self) -> tuple[str, ...]:
        """召回项目作用域：`recall_projects` 非空 → 该列表 + 平台级；空 → 不过滤。"""
        projects = [p.strip() for p in self.recall_projects if p and p.strip()]
        if not projects:
            return ()
        if not any(p == self.global_project_id for p in projects):
            projects.append(self.global_project_id)
        return tuple(dict.fromkeys(projects))

    @property
    def effective_score_threshold(self) -> float:
        """当前模式的相似度阈值：mock 向量用 0.02，真实向量用 0.3。"""
        return self.score_threshold_mock if self.is_mock_embed else self.score_threshold

    @property
    def effective_rerank_model(self) -> str:
        """重排实际使用的模型：RERANK_MODEL 缺省复用 LLM_MODEL。"""
        return (self.rerank_model or "").strip() or self.llm_model

    @property
    def llm_blocked(self) -> bool:
        """LLM 是否处于「不可真实调用」状态（mock / retrieval_only / 无 Key）。"""
        return self.llm_provider != "relay" or not self.llm_api_key

    def with_overrides(self, **kwargs: Any) -> "Settings":
        """派生新配置（值相同的不报错，未知键抛 TypeError）。"""
        valid = {k: v for k, v in kwargs.items() if v is not None}
        if not valid:
            return self
        known = {f.name for f in fields(self)}
        unknown = set(valid) - known
        if unknown:
            raise TypeError(f"未知配置项: {sorted(unknown)}")
        return replace(self, **valid)

    def safe_dict(self) -> dict[str, Any]:
        """可安全写入日志/报告的配置快照（密钥掩码、路径转字符串）。"""
        data: dict[str, Any] = {}
        for f in fields(self):
            value = getattr(self, f.name)
            if f.name in {"llm_api_key", "embed_api_key"}:
                data[f.name] = mask_secret(value)
            elif isinstance(value, Path):
                data[f.name] = str(value)
            elif isinstance(value, tuple):
                data[f.name] = list(value)
            else:
                data[f.name] = value
        data["effective_score_threshold"] = self.effective_score_threshold
        data["effective_rerank_model"] = self.effective_rerank_model
        return data


def load_settings(
    env_file: str | os.PathLike[str] | None = None,
    overrides: dict[str, Any] | None = None,
) -> Settings:
    """构建配置：读 --env-file → 读环境变量 → 应用 overrides。

    输入：
        env_file  : 参考工程 .env 路径（只读；密钥不入库）。None → 只用进程环境变量
        overrides : CLI 显式参数（优先级最高）
    输出：Settings。
    """
    load_env_file(env_file)

    data_dir = Path(_env("DATA_DIR", str(DEFAULT_DATA_DIR)))
    chroma_raw = _env("CHROMA_DIR", "")
    chroma_dir = Path(chroma_raw) if chroma_raw else data_dir / "chroma"

    settings = Settings(
        llm_provider=_env("LLM_PROVIDER", "relay").strip().lower(),
        llm_base_url=normalize_base_url(_env("LLM_BASE_URL", "https://relay.example.com/v1"), require_v1=True),
        llm_model=_env("LLM_MODEL", "k3").strip() or "k3",
        llm_api_key=_env("LLM_API_KEY", ""),
        llm_temperature=_env_float("LLM_TEMPERATURE", 0.2),
        llm_max_tokens=_env_int("LLM_MAX_TOKENS", 4096),
        llm_user_agent=_env("LLM_USER_AGENT", ""),
        context_max_chars=_env_int("CONTEXT_MAX_CHARS", 6000),
        embed_provider=_env("EMBED_PROVIDER", "zhipu").strip().lower(),
        embed_base_url=normalize_base_url(_env("EMBED_BASE_URL", "https://open.bigmodel.cn/api/paas/v4")),
        embed_model=_env("EMBED_MODEL", "embedding-2").strip() or "embedding-2",
        embed_api_key=_env("EMBED_API_KEY", ""),
        embed_dim=_env_int("EMBED_DIM", 1024),
        embed_batch_size=_env_int("EMBED_BATCH_SIZE", 16),
        chunk_size=_env_int("CHUNK_SIZE", 500),
        chunk_overlap=_env_int("CHUNK_OVERLAP", 80),
        chunk_min_length=_env_int("CHUNK_MIN_LENGTH", 30),
        dedup_similarity=_env_float("DEDUP_SIMILARITY", _env_float("DEDUP_SIMILARITY_THRESHOLD", 0.9)),
        mask_token=_env("MASK_TOKEN", "***"),
        mask_amount=_env_bool("MASK_AMOUNT", False),
        sensitive_words=tuple(_parse_list(_env("SENSITIVE_WORDS", ""))),
        retriever_mode=_env("RETRIEVER_MODE", "hybrid").strip().lower(),
        top_k=_env_int("TOP_K", 5),
        recall_per_route=_env_int("RECALL_PER_ROUTE", 15),
        rrf_k=_env_int("RRF_K", 60),
        max_per_knowledge=_env_int("MAX_PER_KNOWLEDGE", 3),
        score_threshold=_env_float("SCORE_THRESHOLD", 0.3),
        score_threshold_mock=_env_float("SCORE_THRESHOLD_MOCK", 0.02),
        rerank_enabled=_env_bool("RERANK_ENABLED", True),
        rerank_candidates=_env_int("RERANK_CANDIDATES", 15),
        rerank_top_n=_env_int("RERANK_TOP_N", 5),
        rerank_model=_env("RERANK_MODEL", ""),
        rerank_temperature=_env_float("RERANK_TEMPERATURE", 0.0),
        rerank_timeout_ms=_env_int("RERANK_TIMEOUT_MS", 1500),
        rerank_max_tokens=_env_int("RERANK_MAX_TOKENS", 128),
        rerank_max_chars_per_doc=_env_int("RERANK_MAX_CHARS_PER_DOC", 200),
        vector_store=_env("VECTOR_STORE", "chroma").strip().lower(),
        data_dir=data_dir,
        chroma_dir=chroma_dir,
        chroma_collection=_env("CHROMA_COLLECTION", "property_kb") or "property_kb",
        pg_dsn=_env("PG_DSN", ""),
        pg_table=_env("PG_TABLE", "knowledge_chunks") or "knowledge_chunks",
        corpus_dir=Path(_env("CORPUS_DIR", str(DEFAULT_CORPUS_DIR))),
        corpus_enabled=_env_bool("CORPUS_ENABLED", True),
        global_project_id=_env("GLOBAL_PROJECT_ID", "ALL").strip() or "ALL",
        default_project_id=_env("DEFAULT_PROJECT_ID", "").strip(),
        recall_projects=tuple(_parse_list(_env("RECALL_PROJECTS", ""))),
        embed_cache_size=_env_int("EMBED_CACHE_SIZE", 4096),
        embed_retries=_env_int("EMBED_RETRIES", 2),
        embed_fallback_mock=_env_bool("EMBED_FALLBACK_MOCK", True),
        embed_max_consecutive_failures=_env_int("EMBED_MAX_CONSECUTIVE_FAILURES", 3),
        expire_status=_env("EXPIRE_STATUS", "expired").strip() or "expired",
        audit_path=Path(_env("AUDIT_PATH", str(data_dir / "index-audit.jsonl"))),
        rerank_mode=_env("RERANK_MODE", "off").strip().lower(),
        rerank_trigger_top1=_env_float("RERANK_TRIGGER_TOP1", 0.0),
        rerank_trigger_gap=_env_float("RERANK_TRIGGER_GAP", 0.05),
        rerank_low_conf_gap=_env_float("RERANK_LOW_CONF_GAP", 0.05),
        rerank_cache_ttl_s=_env_int("RERANK_CACHE_TTL", 600),
        rerank_cache_size=_env_int("RERANK_CACHE_SIZE", 512),
        answer_threshold=_env_float("ANSWER_THRESHOLD", 0.44),
        answer_min_gap=_env_float("ANSWER_MIN_GAP", 0.03),
        answer_flat_margin=_env_float("ANSWER_FLAT_MARGIN", 0.04),
        answer_gap_rule=_env_bool("ANSWER_GAP_RULE", True),
        answer_coverage_min=_env_float("ANSWER_COVERAGE_MIN", 0.10),
        answer_coverage_rule=_env_bool("ANSWER_COVERAGE_RULE", True),
        answer_timeout_ms=_env_int("ANSWER_TIMEOUT_MS", 20000),
        answer_retry_timeout_ms=_env_int("ANSWER_RETRY_TIMEOUT_MS", 45000),
        answer_retries=_env_int("ANSWER_RETRIES", 1),
        answer_max_chars=_env_int("ANSWER_MAX_CHARS", 400),
        service_host=_env("SERVICE_HOST", "127.0.0.1").strip() or "127.0.0.1",
        service_port=_env_int("SERVICE_PORT", 4110),
        service_token_env=_env("SERVICE_TOKEN_ENV", "RAG_INTERNAL_TOKEN").strip() or "RAG_INTERNAL_TOKEN",
        service_max_top_k=_env_int("SERVICE_MAX_TOP_K", 20),
        knowledge_api=_env("KNOWLEDGE_API", "http://localhost:4000").rstrip("/"),
        knowledge_page_size=min(_env_int("KNOWLEDGE_PAGE_SIZE", 100), 100),
        golden_path=Path(_env("GOLDEN_PATH", str(REPO_DIR / "tests" / "rag" / "golden" / "knowledge-golden.jsonl"))),
        results_path=Path(_env("RESULTS_PATH", str(REPO_DIR / "tests" / "rag" / "results" / "rag-poc.json"))),
    )
    if overrides:
        settings = settings.with_overrides(**overrides)
    # 统一路径规范化（相对路径相对 service 目录）
    settings = settings.with_overrides(
        data_dir=_abs(settings.data_dir),
        chroma_dir=_abs(settings.chroma_dir),
        corpus_dir=_abs(settings.corpus_dir),
        audit_path=_abs(settings.audit_path),
        golden_path=_abs(settings.golden_path),
        golden_v2_path=_abs(settings.golden_v2_path),
        results_path=_abs(settings.results_path),
        results_w31_path=_abs(settings.results_w31_path),
        results_w32_path=_abs(settings.results_w32_path),
    )
    return settings


def _abs(path: Path) -> Path:
    """相对路径 → 相对 services/ai-orchestrator 的绝对路径。"""
    path = Path(path)
    return path if path.is_absolute() else (SERVICE_DIR / path).resolve()
