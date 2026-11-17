"""内部检索/生成服务（W33）：`POST /internal/rag/search` · `POST /internal/rag/answer` · `GET /internal/rag/health`
====================================================================================================================

为什么用标准库 `http.server`（而不是 Fastify/Flask）：
    - AGENTS.md 硬约束：Python 只做 AI 编排，本服务是**内网内部服务**，不对外、不进 OpenAPI；
    - 「不引入新依赖」：`http.server` + `json` 足够承载单机 PoC 的检索/生成调用；
    - 生产再由 Java BFF 调用本服务（契约在 OpenAPI 侧另行定义，本文件不改契约）。

接口：
    POST /internal/rag/search（W32，形状保持不变）
        body : {"question": str, "projectId": str|list, "category"?: str, "topK"?: int}
        200  : {"ok": true, "sources": [{index, chunkId, knowledgeId, title, category,
                categoryName, score, snippet, sectionPath}], "reranked": bool,
                "rerankMode": str, "answerable": bool, "confidence": float,
                "refusalReason"?: str, "degraded": bool, "latencyMs": int,
                "projectId": str, "scope": [...], "guard": {...}}
    POST /internal/rag/answer（W33 新增；复用 search 的检索/重排/guard，不重复实现）
        body : {"question": str, "projectId": str|list, "category"?: str, "topK"?: int,
                "mode"?: "generative"|"retrieval_only"}
        200  : {"ok": true, "answer": str, "sources": [RagSource 8 字段],
                "confidence": float, "answerable": bool, "refusalReason"?: str,
                "versionNotice": bool, "mode": "generative"|"retrieval_only"|"rules",
                "reranked": bool, "rerankMode": str, "degraded": bool,
                "usage": {promptTokens, completionTokens, totalTokens, reasoningTokens, rerankCalls},
                "latencyMs": int, "traceId": "rag-answer-<hash>",
                "promptVersion", "citations", "citationValid", "model", "attempts",
                "temperatureFallback", "versionIntent", "guard", "rerank", "projectId", "scope"}
    GET  /internal/rag/health
        200  : {"ok": true, "status": "ok", "index": {chunks, backend, collection},
                "models": {embed, llm, rerank}, "rerankMode": ..., "guard": {...},
                "llm"/"embedding"/"vectorStore"/"reranker" 组件状态（W33 扩展，无密钥）,
                "promptVersion", "defaults": {projectId, topK}, "uptimeSeconds": int}
        组件 status 只取契约枚举 ok|degraded|down（W34 修复）：重排 off/enabled=false 属策略默认 → ok；
        degraded 只反映真实降级/故障（无 Key/仅检索、mock embedding、重排开启但模型不可用、向量库不可用）。

鉴权（W32 安全底线，两个 POST 与 health 同口径）：
    每个请求必须带 `X-Internal-Token: <token>`；token 只从环境变量
    `RAG_INTERNAL_TOKEN`（可用 `--token-env` 换名）读取。
    **未配置 token → 一律 401（`token_not_configured`）**，避免服务裸奔被内网任意调用；
    token 比较用 `hmac.compare_digest`（常量时间），日志/响应绝不回显 token。

隐私：日志只记录 query 长度 + query_hash + 项目 + 状态码 + 耗时 + answerable/citations 数，
**不落问题原文、不落答案正文**。
"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
import time
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Callable

from .config import Settings, load_settings, mask_secret
from .generation import PROMPT_VERSION, AnswerResult, answer as generate_answer, detect_version_intent
from .guard import AnswerGuard, GuardResult
from .hybrid import HybridRetriever
from .llm import LLMClient
from .reranker import RerankController, RerankDecision
from .store import RetrievedChunk, VectorStore

DEFAULT_TOKEN_ENV = "RAG_INTERNAL_TOKEN"
MAX_BODY_BYTES = 64 * 1024
SERVICE_NAME = "rag-search"
ANSWER_MODES = ("generative", "retrieval_only")


def _now() -> float:
    return time.monotonic()


def query_hash(text: str) -> str:
    """问题 hash（日志用，不落原文）。"""
    return hashlib.sha256((text or "").encode("utf-8")).hexdigest()[:12]


@dataclass
class RetrievalOutcome:
    """一次检索链路结果（search/answer 共用；保证两端口径一致）。"""

    question: str
    chunks: list[RetrievedChunk]
    decision: RerankDecision
    guard: GuardResult
    degraded: bool
    project_id: str = ""
    scope: list[str] = field(default_factory=list)


class RagSearchService:
    """检索 + 生成服务核心（与 HTTP 解耦，便于单测直接调 `search()`/`answer()`）。"""

    def __init__(
        self,
        settings: Settings,
        *,
        retriever: HybridRetriever | None = None,
        rerank_controller: RerankController | None = None,
        guard: AnswerGuard | None = None,
        llm: LLMClient | None = None,
        log_fn: Callable[[str], None] | None = None,
    ) -> None:
        self.settings = settings
        self.log = log_fn or (lambda message: print(message, flush=True))
        self.store = VectorStore(settings)
        self.retriever = retriever or HybridRetriever(settings, store=self.store)
        self.rerank_controller = rerank_controller or RerankController(settings)
        self.guard = guard or AnswerGuard(settings)
        self.llm = llm or LLMClient(settings)
        self.started_at = _now()

    # ---- 校验 ----
    def _validate(self, payload: dict[str, Any]) -> tuple[str, str, str | None, int]:
        """body → (question, project_id, category, top_k)；非法 → ValueError(可读消息)。"""
        question = str(payload.get("question") or "").strip()
        if not question:
            raise ValueError("question 必填且非空")
        if len(question) > 2000:
            raise ValueError("question 超长（>2000 字符）")
        project_id = payload.get("projectId")
        if project_id is None:
            project_id = payload.get("project_id")
        if isinstance(project_id, (list, tuple)):
            project_id = [str(item) for item in project_id if str(item).strip()]
        elif project_id is not None:
            project_id = str(project_id).strip()
        category = payload.get("category")
        category = str(category).strip() if category else None
        top_k = payload.get("topK", payload.get("top_k", self.settings.top_k))
        try:
            top_k = int(top_k or self.settings.top_k)
        except (TypeError, ValueError):
            raise ValueError("topK 必须是整数") from None
        top_k = max(1, min(top_k, max(1, self.settings.service_max_top_k)))
        return question, project_id, category, top_k

    @staticmethod
    def _validate_mode(payload: dict[str, Any]) -> str:
        """body.mode → generative | retrieval_only（缺省 generative；其它值 400）。"""
        raw = payload.get("mode")
        mode = str(raw).strip().lower() if raw is not None else "generative"
        if mode not in ANSWER_MODES:
            raise ValueError("mode 只能是 generative 或 retrieval_only")
        return mode

    # ---- 检索（search/answer 共用，避免两套实现漂移）----
    def _retrieve(self, question: str, project_id: Any, category: str | None, top_k: int) -> RetrievalOutcome:
        """混合检索 → 条件重排 → answerable guard（W32 口径，原样复用）。"""
        mode = self.rerank_controller.mode
        candidate_k = top_k if mode == "off" else max(top_k, self.settings.rerank_candidates)
        hybrid = self.retriever.search(
            question,
            top_k=candidate_k,
            category=category,
            project_id=project_id,
        )
        decision = self.rerank_controller.apply(
            question,
            hybrid.chunks,
            top_n=top_k,
            project_id=hybrid.scope.project_id or (project_id or ""),
        )
        final_chunks = decision.chunks
        guard = self.guard.evaluate(final_chunks, question=question)
        degraded = bool(
            decision.degraded
            or hybrid.backend != "chroma"
            or (
                mode != "off"
                and self.rerank_controller.reranker is not None
                and not self.rerank_controller.reranker.is_enabled
            )
        )
        return RetrievalOutcome(
            question=question,
            chunks=final_chunks,
            decision=decision,
            guard=guard,
            degraded=degraded,
            project_id=hybrid.scope.project_id or "",
            scope=list(hybrid.scope.values),
        )

    # ---- 检索端点 ----
    def search(self, payload: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        """执行一次检索 → (HTTP 状态码, 响应体)。"""
        started = _now()
        try:
            question, project_id, category, top_k = self._validate(payload)
        except ValueError as exc:
            return 400, {"ok": False, "error": {"code": "E-INVALID-PARAM", "message": str(exc)}}

        outcome = self._retrieve(question, project_id, category, top_k)
        decision, guard, final_chunks = outcome.decision, outcome.guard, outcome.chunks
        latency_ms = int((_now() - started) * 1000)
        sources = [
            {
                "index": index,
                "chunkId": chunk.chunk_id,
                "knowledgeId": chunk.knowledge_id,
                "title": chunk.title,
                "category": chunk.category,
                "categoryName": chunk.category_name,
                "score": round(float(chunk.score), 4),
                "snippet": chunk.text.strip()[:160],
                "sectionPath": chunk.section_path,
            }
            for index, chunk in enumerate(final_chunks, start=1)
        ]
        body: dict[str, Any] = {
            "ok": True,
            "sources": sources,
            "reranked": bool(decision.applied),
            "rerankMode": decision.mode,
            "answerable": bool(guard.answerable),
            "confidence": round(float(guard.confidence), 4),
            "degraded": outcome.degraded,
            "latencyMs": latency_ms,
            "projectId": outcome.project_id,
            "scope": list(outcome.scope),
            "guard": guard.to_dict(),
            "rerank": decision.to_dict(),
            "traceId": f"{SERVICE_NAME}-{query_hash(question)}",
        }
        if not guard.answerable:
            body["refusalReason"] = guard.refusal_reason
        self.log(
            f"[service] search ok q_hash={query_hash(question)} q_len={len(question)} "
            f"topK={top_k} sources={len(sources)} answerable={guard.answerable} "
            f"mode={decision.mode} degraded={outcome.degraded} {latency_ms}ms"
        )
        return 200, body

    # ---- 生成端点（W33）----
    def answer(self, payload: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        """执行一次「检索 + 生成 + 引用校验 + answerable 终判」→ (HTTP 状态码, 响应体)。"""
        started = _now()
        try:
            question, project_id, category, top_k = self._validate(payload)
            requested_mode = self._validate_mode(payload)
        except ValueError as exc:
            return 400, {"ok": False, "error": {"code": "E-INVALID-PARAM", "message": str(exc)}}

        outcome = self._retrieve(question, project_id, category, top_k)
        result: AnswerResult = generate_answer(
            question,
            outcome.chunks,
            self.settings,
            self.llm,
            guard=outcome.guard,
            mode=requested_mode,
        )
        latency_ms = int((_now() - started) * 1000)
        degraded = bool(outcome.degraded or result.degraded)
        body: dict[str, Any] = {
            "ok": True,
            "answer": result.answer,
            "sources": [self._source_row(source) for source in result.sources],
            "confidence": round(float(result.confidence or outcome.guard.confidence), 4),
            "answerable": bool(result.answerable),
            "versionNotice": bool(result.version_notice),
            "mode": result.mode,
            "reranked": bool(outcome.decision.applied),
            "rerankMode": outcome.decision.mode,
            "degraded": degraded,
            "usage": self._usage_payload(result, outcome.decision),
            "latencyMs": latency_ms,
            "traceId": f"rag-answer-{query_hash(question)}",
            "promptVersion": result.prompt_version or PROMPT_VERSION,
            "citations": list(result.citations),
            "citationValid": bool(result.citation_valid),
            "model": result.model or self.settings.llm_model,
            "attempts": int(result.attempts),
            "temperatureFallback": bool(result.temperature_fallback),
            "versionIntent": detect_version_intent(question),
            "projectId": outcome.project_id,
            "scope": list(outcome.scope),
            "guard": outcome.guard.to_dict(),
            "rerank": outcome.decision.to_dict(),
        }
        if result.refusal_reason:
            body["refusalReason"] = result.refusal_reason
        if result.error:
            body["error"] = result.error
        if result.detail:
            body["detail"] = dict(result.detail)
        self.log(
            f"[service] answer ok q_hash={query_hash(question)} q_len={len(question)} topK={top_k} "
            f"req_mode={requested_mode} mode={result.mode} answerable={result.answerable} "
            f"cites={len(result.citations)} versionNotice={result.version_notice} "
            f"degraded={degraded} attempts={result.attempts} {latency_ms}ms"
        )
        return 200, body

    # ---- 响应映射辅助 ----
    @staticmethod
    def _source_row(source: dict[str, Any]) -> dict[str, Any]:
        """AnswerResult.sources → 契约 RagSource 8 字段（数值/字符串规范化）。"""
        return {
            "index": int(source.get("index") or 0),
            "knowledgeId": str(source.get("knowledgeId") or ""),
            "title": str(source.get("title") or ""),
            "version": str(source.get("version") or ""),
            "owner": str(source.get("owner") or ""),
            "category": str(source.get("category") or ""),
            "score": round(float(source.get("score") or 0.0), 4),
            "snippet": str(source.get("snippet") or ""),
        }

    @staticmethod
    def _usage_payload(result: AnswerResult, decision: RerankDecision) -> dict[str, Any]:
        """生成/重排用量 → 契约 RagUsage（camelCase；额外 reasoningTokens 用于成本如实记录）。"""
        raw = result.usage or {}
        details = raw.get("completion_tokens_details") or {}
        prompt_tokens = int(raw.get("prompt_tokens") or 0)
        completion_tokens = int(raw.get("completion_tokens") or 0)
        return {
            "promptTokens": prompt_tokens,
            "completionTokens": completion_tokens,
            "totalTokens": int(raw.get("total_tokens") or (prompt_tokens + completion_tokens)),
            "reasoningTokens": int((details or {}).get("reasoning_tokens") or 0),
            "rerankCalls": 1 if decision.triggered else 0,
        }

    # ---- 健康 ----
    def _llm_mode(self) -> str:
        """LLM 实际模式：relay（可真实调用）/ mock / retrieval_only（无 Key 或显式配置）。"""
        provider = (self.settings.llm_provider or "").strip().lower()
        if provider == "mock":
            return "mock"
        if provider != "relay" or not self.settings.llm_api_key:
            return "retrieval_only"
        return "relay"

    def _reranker_enabled(self) -> bool:
        """重排是否真实可用（off 之外且模型可调用）。"""
        if self.rerank_controller.mode == "off":
            return False
        reranker = self.rerank_controller.reranker
        return bool(reranker is not None and getattr(reranker, "is_enabled", False))

    def _reranker_component(self) -> tuple[str, str]:
        """重排组件 → (status, detail)。

        W34 语义修复（task-82）：`status` 只允许契约枚举 `ok|degraded|down`。
        `mode=off`（`enabled=false`）是**策略默认**（W30/W32 实测 k3 推理型在线不可用 → 重排默认关），
        **不是故障**，故报 `ok` + 人话说明；只有「模式开启但模型不可调用」才报 `degraded`（失败透传原序）。
        """
        controller = self.rerank_controller
        mode = str(controller.mode or "off").strip().lower() or "off"
        if mode == "off":
            return "ok", "重排已关闭（enabled=false，策略默认）"
        if self._reranker_enabled():
            return "ok", f"重排已启用（mode={mode}，enabled=true）"
        return "degraded", f"重排模式={mode} 但重排模型不可调用（请求按原序透传，不阻塞问答）"

    def health(self) -> dict[str, Any]:
        """索引/模型/重排/阈值/生成组件快照（不含密钥、不含问题）。

        W34 语义修复（task-82）：组件 `status` 一律取契约枚举 `ok|degraded|down`
        （此前 reranker 关闭报 `off`、embedding mock 报 `mock` 会被 Java `RagComponent`
        契约校验判为非法值，并让 `/rag/health` 误报 `degraded=true`）。
        `degraded` 只反映**真实降级/故障**：LLM 不可生成、向量库不可用、重排开启但不可调用；
        策略默认关闭（重排 off）**不算降级**。`llm.mode` 如实：无 Key/仅检索 → `retrieval_only`，mock → `mock`，
        只有 provider=relay 且 Key 已配置才报 `relay`。
        """
        chunk_count = 0
        store_error = ""
        try:
            chunk_count = int(self.store.count())
        except Exception as exc:  # noqa: BLE001 - 健康检查不因存储异常整体失败
            store_error = f"{type(exc).__name__}: {str(exc)[:120]}"
            self.log(f"[service][warn] 统计 chunk 数失败：{store_error}")
        llm_mode = self._llm_mode()
        llm_ok = llm_mode == "relay"
        reranker_enabled = self._reranker_enabled()
        reranker_status, reranker_detail = self._reranker_component()
        store_ok = chunk_count > 0 and not store_error
        llm_status = "ok" if llm_ok else "degraded"
        embed_status = "degraded" if self.settings.is_mock_embed else "ok"
        store_status = "ok" if store_ok else "down"
        component_statuses = [llm_status, embed_status, store_status, reranker_status]
        if store_status == "down":
            status = "down"
        elif all(value == "ok" for value in component_statuses):
            status = "ok"
        else:
            status = "degraded"
        return {
            "ok": True,
            "status": status,
            "service": SERVICE_NAME,
            "index": {
                "chunks": chunk_count,
                "backend": self.store.backend,
                "collection": self.settings.chroma_collection,
                "init_warnings": list(getattr(self.store, "init_warnings", []) or []),
            },
            "models": {
                "embed": f"{self.settings.embed_provider}:{self.settings.embed_model}",
                "embed_dim": self.settings.embed_dim,
                "llm": self.settings.llm_model,
                "rerank": self.settings.effective_rerank_model,
            },
            # ---- W33 组件状态（Java /rag/health 聚合用）/ W34 枚举与语义修复 ----
            "llm": {
                "provider": self.settings.llm_provider,
                "model": self.settings.llm_model,
                "mode": llm_mode,
                "canCall": llm_ok,
                "answerMaxTokens": self.settings.llm_max_tokens,
                "temperature": self.settings.llm_temperature,
                "timeoutMs": self.settings.answer_timeout_ms,
                "retries": self.settings.answer_retries,
                "status": llm_status,
                "detail": "可真实生成（provider=relay 且已配置中转凭据）" if llm_ok else (
                    "provider=mock：本地 mock，未真实生成（PoC/演练）"
                    if llm_mode == "mock"
                    else "生成不可用：未配置中转凭据或 provider=retrieval_only → 仅检索模式（不报 relay）"
                ),
            },
            "embedding": {
                "provider": self.settings.embed_provider,
                "model": self.settings.embed_model,
                "dim": self.settings.embed_dim,
                "mock": self.settings.is_mock_embed,
                "status": embed_status,
                "detail": "mock embedding（离线/单测，非真实向量）"
                if self.settings.is_mock_embed
                else f"真实 embedding（{self.settings.embed_provider}:{self.settings.embed_model}）",
            },
            "vectorStore": {
                "backend": self.store.backend,
                "collection": self.settings.chroma_collection,
                "count": chunk_count,
                "status": store_status,
                "error": store_error,
            },
            "reranker": {
                "mode": self.rerank_controller.mode,
                "enabled": reranker_enabled,
                "model": self.settings.effective_rerank_model,
                "timeoutMs": self.settings.rerank_timeout_ms,
                "status": reranker_status,
                "detail": reranker_detail,
            },
            "promptVersion": PROMPT_VERSION,
            "answer": {
                "maxChars": self.settings.answer_max_chars,
                "citationRetries": self.settings.answer_retries,
                "timeoutMs": self.settings.answer_timeout_ms,
                "retryTimeoutMs": self.settings.answer_retry_timeout_ms,
            },
            "rerankMode": self.rerank_controller.mode,
            "rerankTimeoutMs": self.settings.rerank_timeout_ms,
            "guard": {
                "answerThreshold": self.settings.answer_threshold,
                "answerMinGap": self.settings.answer_min_gap,
                "answerFlatMargin": self.settings.answer_flat_margin,
                "answerCoverageMin": self.settings.answer_coverage_min,
                "gapRule": self.settings.answer_gap_rule,
                "coverageRule": self.settings.answer_coverage_rule,
            },
            "defaults": {
                "projectId": self.settings.default_project_id or self.settings.global_project_id,
                "topK": self.settings.top_k,
                "globalProjectId": self.settings.global_project_id,
            },
            "recallStatus": list(self.settings.recall_status),
            "uptimeSeconds": int(_now() - self.started_at),
        }


class _Handler(BaseHTTPRequestHandler):
    """HTTP 路由（search/answer/health 三个内部端点 + 统一 401）。"""

    server_version = "RagInternal/1.1"
    protocol_version = "HTTP/1.1"
    service: RagSearchService = None  # type: ignore[assignment]
    token: str = ""
    token_env: str = DEFAULT_TOKEN_ENV
    log_fn: Callable[[str], None] | None = None
    #: 当前请求体是否已被读取/丢弃（HTTP/1.1 keep-alive 下未读 body 会串成下一条请求行 → 501）
    _body_consumed: bool = False

    # ---- 基础 ----
    def log_message(self, fmt: str, *args: Any) -> None:  # noqa: ANN401 - 覆盖 stdlib 签名
        """屏蔽 stdlib 访问日志（避免把 query 原文打进 stderr）。"""
        return

    def _drain_body(self) -> None:
        """读掉当前请求体（401/404 等错误响应前必须调用）。

        W34 修复（task-82，实测发现）：本服务是 `HTTP/1.1` + keep-alive。此前 `POST /internal/rag/reindex`
        （编排层未暴露的端点，Java reindex 降级路径必经）走 404 分支时**没有读请求体**，残留 JSON 字节被
        当成下一条请求行解析 → 同一连接上的下一次 `GET /internal/rag/health` 被 stdlib 判为
        「不支持的 method」返回 **501**。后果：Java 侧 `reindex` 失败后紧接着的健康探测误报
        「编排层不可达」→ `/rag/health` 假 degraded（违反「degraded 只反映真实故障」语义）。
        """
        if self._body_consumed:
            return
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = -1
        if length <= 0:
            self._body_consumed = True
            return
        if length > MAX_BODY_BYTES:
            # 超大 body 不读：直接断开连接，绝不把未知字节当作下一条请求
            self._body_consumed = True
            self.close_connection = True
            return
        try:
            self.rfile.read(length)
        except OSError:  # pragma: no cover - 客户端提前断开
            self.close_connection = True
        self._body_consumed = True

    def _send(self, status: int, body: dict[str, Any]) -> None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("X-Rag-Service", SERVICE_NAME)
        self.end_headers()
        self.wfile.write(data)
        # 安全网：错误路径若仍有未读 body → 关闭连接（keep-alive 串包防护，宁可少复用不可串包）
        if not self._body_consumed:
            try:
                pending = int(self.headers.get("Content-Length") or 0)
            except ValueError:
                pending = -1
            if pending != 0:
                self.close_connection = True

    def _auth_failure(self) -> dict[str, Any] | None:
        """鉴权：通过 → None；失败 → 401 错误体（由调用方先 drain body 再发送）。"""
        if not self.token:
            return {
                "ok": False,
                "error": {
                    "code": "token_not_configured",
                    "message": f"服务未配置内部 token（环境变量 {self.token_env} 为空）→ 全部请求 401，"
                    "避免服务裸奔；请注入 token 后重启",
                },
            }
        provided = self.headers.get("X-Internal-Token") or ""
        if provided and hmac.compare_digest(provided, self.token):
            return None
        return {
            "ok": False,
            "error": {
                "code": "token_invalid",
                "message": "缺少或错误的 X-Internal-Token（401）",
            },
        }

    # ---- 路由 ----
    def do_GET(self) -> None:  # noqa: N802 - stdlib 约定
        self._body_consumed = False
        if self.path.split("?")[0] != "/internal/rag/health":
            self._drain_body()
            self._send(404, {"ok": False, "error": {"code": "not_found", "message": "仅支持 /internal/rag/health"}})
            return
        failure = self._auth_failure()
        if failure is not None:
            self._drain_body()
            self._send(401, failure)
            return
        self._send(200, self.service.health())

    def do_POST(self) -> None:  # noqa: N802 - stdlib 约定
        self._body_consumed = False
        path = self.path.split("?")[0]
        if path not in {"/internal/rag/search", "/internal/rag/answer"}:
            self._drain_body()
            self._send(
                404,
                {"ok": False, "error": {"code": "not_found", "message": "仅支持 /internal/rag/search、/internal/rag/answer"}},
            )
            return
        failure = self._auth_failure()
        if failure is not None:
            self._drain_body()
            self._send(401, failure)
            return
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            self.close_connection = True
            self._send(400, {"ok": False, "error": {"code": "E-INVALID-PARAM", "message": "Content-Length 非法"}})
            return
        if length <= 0 or length > MAX_BODY_BYTES:
            self.close_connection = True
            self._body_consumed = True
            self._send(400, {"ok": False, "error": {"code": "E-INVALID-PARAM", "message": f"请求体长度非法（{length}）"}})
            return
        raw = self.rfile.read(length)
        self._body_consumed = True
        try:
            payload = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            self._send(400, {"ok": False, "error": {"code": "E-INVALID-PARAM", "message": f"JSON 解析失败：{exc}"}})
            return
        if not isinstance(payload, dict):
            self._send(400, {"ok": False, "error": {"code": "E-INVALID-PARAM", "message": "请求体必须是 JSON 对象"}})
            return
        try:
            handler = self.service.search if path == "/internal/rag/search" else self.service.answer
            status, body = handler(payload)
        except Exception as exc:  # noqa: BLE001 - 服务端异常 → 500，不泄漏堆栈
            (self.log_fn or print)(f"[service][error] 内部错误：{type(exc).__name__}: {str(exc)[:200]}")
            status, body = 500, {"ok": False, "error": {"code": "E-INTERNAL", "message": "服务内部错误"}}
        self._send(status, body)


def create_server(
    service: RagSearchService,
    *,
    host: str = "127.0.0.1",
    port: int = 4110,
    token: str = "",
    token_env: str = DEFAULT_TOKEN_ENV,
    log_fn: Callable[[str], None] | None = None,
) -> ThreadingHTTPServer:
    """构造 HTTP 服务（token 为空 → 所有请求 401，这是**预期**的 fail-closed）。"""
    handler = type(
        "BoundHandler",
        (_Handler,),
        {"service": service, "token": token or "", "token_env": token_env, "log_fn": service.log},
    )
    class _BoundedThreadingHTTPServer(ThreadingHTTPServer):
        # 50 并发压测暴露：socketserver 默认 listen backlog=5，accept 排队溢出会被内核拒连
        request_queue_size = 128
        daemon_threads = True
        allow_reuse_address = True

    httpd = _BoundedThreadingHTTPServer((host, int(port)), handler)
    httpd.daemon_threads = True
    return httpd


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="RAG 内部服务（POST /internal/rag/search、POST /internal/rag/answer、GET /internal/rag/health）"
    )
    parser.add_argument("--env-file", default=None, help="参考工程 .env（只读；密钥不入库）")
    parser.add_argument("--host", default=None, help="监听地址（缺省 127.0.0.1，仅内网）")
    parser.add_argument("--port", type=int, default=None, help="监听端口（缺省 4110）")
    parser.add_argument("--token-env", default=None, help=f"token 环境变量名（缺省 {DEFAULT_TOKEN_ENV}）")
    parser.add_argument("--project", default=None, help="缺省项目作用域（请求未带 projectId 时使用）")
    parser.add_argument("--top-k", type=int, default=None, help="缺省 topK")
    parser.add_argument("--rerank", default=None, choices=["off", "conditional", "always", "shadow"], help="重排模式（缺省 off）")
    parser.add_argument("--dry-run", action="store_true", help="只打印配置（密钥掩码）后退出，不起服务")
    return parser


def main(argv: list[str] | None = None) -> int:
    """CLI 入口（`python -m rag.service --port 4110`）。"""
    args = build_parser().parse_args(argv)
    overrides: dict[str, Any] = {}
    if args.project:
        overrides["default_project_id"] = args.project
    if args.top_k:
        overrides["top_k"] = args.top_k
    if args.rerank:
        overrides["rerank_mode"] = args.rerank
    settings = load_settings(env_file=args.env_file, overrides=overrides)
    token_env = args.token_env or settings.service_token_env or DEFAULT_TOKEN_ENV
    token = (os.environ.get(token_env) or "").strip()
    host = args.host or settings.service_host
    port = int(args.port or settings.service_port)

    if args.dry_run:
        print("[service][dry-run] 配置快照（密钥已掩码）：")
        print(json.dumps(settings.safe_dict(), ensure_ascii=False, indent=2))
        print(f"[service][dry-run] token 环境变量 {token_env}：{'已配置' if token else '未配置（启动后所有请求 401）'}")
        print(f"[service][dry-run] 监听 {host}:{port}；重排模式={settings.effective_rerank_mode}；promptVersion={PROMPT_VERSION}")
        try:
            store = VectorStore(settings)
            print(f"[service][dry-run] 向量库 backend={store.backend} chunks={store.count()}")
        except Exception as exc:  # noqa: BLE001 - dry-run 不因存储不可用崩溃
            print(f"[service][dry-run] 向量库不可用：{type(exc).__name__}: {str(exc)[:160]}")
        print(f"[service][dry-run] llm_key={mask_secret(settings.llm_api_key)} embed_key={mask_secret(settings.embed_api_key)}")
        return 0

    service = RagSearchService(settings)
    httpd = create_server(service, host=host, port=port, token=token, token_env=token_env)
    health = service.health()
    print(
        f"[service] 启动 {host}:{port}｜chunks={health['index']['chunks']}｜backend={health['index']['backend']}"
        f"｜embed={health['models']['embed']}｜llm={health['llm']['model']}({health['llm']['mode']})"
        f"｜rerankMode={health['rerankMode']}｜promptVersion={health['promptVersion']}"
        f"｜token={'已配置' if token else '未配置→401'}",
        flush=True,
    )
    if not token:
        print(
            f"[service][warn] 环境变量 {token_env} 未设置：所有请求将返回 401（fail-closed，防止服务裸奔）",
            flush=True,
        )
    try:
        httpd.serve_forever(poll_interval=0.2)
    except KeyboardInterrupt:  # pragma: no cover - 手动中断
        print("[service] 收到中断，关闭服务", flush=True)
    finally:
        httpd.server_close()
    return 0


__all__ = [
    "ANSWER_MODES",
    "DEFAULT_TOKEN_ENV",
    "RagSearchService",
    "RetrievalOutcome",
    "create_server",
    "main",
]

if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
