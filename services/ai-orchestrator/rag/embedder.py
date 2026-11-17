"""Embedding 模块（智谱 embedding-2 / 1024 维 / batch 16 / LRU + mock 降级）
================================================================================

职责：把文本批量向量化，并按「模型 + 文本 hash」缓存，避免重复计费。

W31 升级（5 项）：
    1. **batch 16**：`EMBED_BATCH_SIZE` 默认 16（智谱实测可用），按批调用；
    2. **LRU 缓存**：进程内 `OrderedDict` LRU（键 = provider+model+text 的 sha256，
       不落明文；容量 `EMBED_CACHE_SIZE`），命中即不重复调用；磁盘缓存
       `data_dir/embed_cache.json` 继续保留（跨进程/重复 ingest 复用）；
    3. **失败重试**：单批失败按指数退避重试 `EMBED_RETRIES`（默认 2 次，0.5s/1s）；
    4. **维度校验**：逐条校验 `len(vec) == EMBED_DIM`，返回条数不符/维度不符/空向量
       直接抛 `EmbeddingError`（不带病入库）；
    5. **失败自动降级 mock 并告警**：重试耗尽且 `EMBED_FALLBACK_MOCK=true` 时，该批
       改用确定性哈希向量继续完成索引，同时记录 `degraded=True` + 告警文案
       （**绝不静默**：ingest/selfcheck/报告都会带出 `fallback_mock_texts`）。

实测（2026-11-16）：`EMBED_BASE_URL=https://open.bigmodel.cn/api/paas/v4`、
`EMBED_MODEL=embedding-2`、`EMBED_DIM=1024`、batch 16 返回 200；单批 ~3.7s（冷启）。

mock 模式：确定性哈希向量（sha256 展开 → 归一化），离线可跑通全链路；
相似度分布与真实 embedding 不同，故阈值切到 `SCORE_THRESHOLD_MOCK=0.02`。
"""

from __future__ import annotations

import hashlib
import json
import math
import time
from collections import OrderedDict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

from .config import Settings


class EmbeddingError(RuntimeError):
    """Embedding 调用失败（超时/上游错误）。"""


class EmbeddingValidationError(EmbeddingError):
    """Embedding 结果校验失败（维度不符/空向量/条数不符）。

    属**配置/契约错误**（EMBED_MODEL/EMBED_DIM 配错），不允许降级 mock 掩盖：
    `EMBED_FALLBACK_MOCK` 只对传输类失败生效，校验错误一律直接抛出。
    """


@dataclass
class EmbedStats:
    """一次批量向量化的统计（给 ingest/报告用）。"""

    texts: int = 0
    batches: int = 0
    cache_hits: int = 0
    api_calls: int = 0
    retries: int = 0
    latency_ms: int = 0
    dim: int = 0
    provider: str = ""
    fallback_mock_texts: int = 0  # 失败降级为 mock 向量的条数（>0 必须告警）
    degraded: bool = False
    errors: int = 0

    def to_dict(self) -> dict[str, Any]:
        return {
            "texts": self.texts,
            "batches": self.batches,
            "cache_hits": self.cache_hits,
            "api_calls": self.api_calls,
            "retries": self.retries,
            "latency_ms": self.latency_ms,
            "dim": self.dim,
            "provider": self.provider,
            "fallback_mock_texts": self.fallback_mock_texts,
            "degraded": self.degraded,
            "errors": self.errors,
        }


def _mock_vector(text: str, dim: int) -> list[float]:
    """确定性哈希向量：同一文本永远同一向量，长度 dim，单位化。"""
    values: list[float] = []
    seed = text.encode("utf-8")
    counter = 0
    while len(values) < dim:
        digest = hashlib.sha256(seed + counter.to_bytes(4, "little")).digest()
        for i in range(0, len(digest), 4):
            chunk = digest[i : i + 4]
            values.append((int.from_bytes(chunk, "big") / 2**32) * 2 - 1)
        counter += 1
    values = values[:dim]
    norm = math.sqrt(sum(v * v for v in values)) or 1.0
    return [v / norm for v in values]


def _hash_key(provider: str, model: str, text: str) -> str:
    """缓存键：provider + 模型 + 文本 的 sha256（不落明文文本）。

    必须带 provider：mock 与真实 embedding 维度相同、模型名相同，若只按 model+text
    缓存，会把哈希假向量当成真实向量复用（PoC 实测踩过）。
    """
    return hashlib.sha256(f"{provider}\x00{model}\x00{text}".encode("utf-8")).hexdigest()


def vector_norm(vec: list[float]) -> float:
    """向量 L2 范数（自检空向量用）。"""
    return math.sqrt(sum(float(v) * float(v) for v in vec))


class Embedder:
    """批量 Embedding（LRU + 磁盘缓存 + 重试 + 维度校验 + mock 降级）。

    输入：
        settings  : 配置（provider/base_url/model/dim/batch/缓存容量/重试/降级开关）
        cache_path: 缓存文件路径；None → `data_dir/embed_cache.json`
        alert_fn  : 告警回调（默认收集到 self.alerts；ingest 会打印并写 manifest）
    """

    def __init__(
        self,
        settings: Settings,
        cache_path: Path | None = None,
        alert_fn: Callable[[str], None] | None = None,
    ) -> None:
        self.settings = settings
        self.provider = settings.embed_provider
        self.dim = settings.embed_dim
        self.cache_path = cache_path or (settings.data_dir / "embed_cache.json")
        self.max_cache = max(0, int(settings.embed_cache_size))
        self.alert_fn = alert_fn
        self._lru: OrderedDict[str, list[float]] = OrderedDict()
        self._disk: dict[str, list[float]] = {}
        self._dirty = False
        self._client = None
        self.alerts: list[str] = []
        self.degraded = False
        self._load_cache()

    # ---- 缓存 ----
    def _load_cache(self) -> None:
        try:
            if self.cache_path.is_file():
                raw = json.loads(self.cache_path.read_text(encoding="utf-8"))
                if isinstance(raw, dict):
                    self._disk = {k: list(map(float, v)) for k, v in raw.items() if isinstance(v, list)}
        except Exception:  # pragma: no cover - 缓存损坏不影响主链路
            self._disk = {}

    def save_cache(self) -> None:
        """落盘缓存（ingest 结束时调用；PoC 复跑可省 embedding 费用）。"""
        if not self._dirty:
            return
        try:
            self.cache_path.parent.mkdir(parents=True, exist_ok=True)
            self.cache_path.write_text(json.dumps(self._disk, ensure_ascii=False), encoding="utf-8")
            self._dirty = False
        except Exception:  # pragma: no cover
            pass

    def _cache_get(self, key: str) -> list[float] | None:
        """LRU → 磁盘两级查找（命中提升到 LRU 头部）。"""
        vec = self._lru.get(key)
        if vec is None:
            vec = self._disk.get(key)
            if vec is None:
                return None
        else:
            self._lru.move_to_end(key)
        self._cache_put_lru(key, vec)
        return vec

    def _cache_put_lru(self, key: str, vec: list[float]) -> None:
        if self.max_cache <= 0:
            return
        self._lru[key] = vec
        self._lru.move_to_end(key)
        while len(self._lru) > self.max_cache:
            self._lru.popitem(last=False)  # 淘汰最久未使用

    def _cache_put(self, key: str, vec: list[float]) -> None:
        self._disk[key] = vec
        self._dirty = True
        self._cache_put_lru(key, vec)

    @property
    def lru_size(self) -> int:
        """LRU 当前条目数。"""
        return len(self._lru)

    @property
    def disk_cache_size(self) -> int:
        """磁盘缓存条目数。"""
        return len(self._disk)

    @property
    def is_mock(self) -> bool:
        return self.provider == "mock"

    def _alert(self, message: str) -> None:
        """记录告警（并回调上层，例如 ingest 打印 + 写 manifest）。"""
        self.alerts.append(message)
        if self.alert_fn is not None:
            try:
                self.alert_fn(message)
            except Exception:  # pragma: no cover - 告警回调异常不影响索引
                pass

    # ---- 客户端 ----
    def _get_client(self):
        if self._client is None:
            try:
                from openai import OpenAI
            except Exception as exc:  # pragma: no cover
                raise EmbeddingError("缺少 openai 依赖，无法调用 Embedding") from exc
            self._client = OpenAI(
                base_url=self.settings.embed_base_url,
                api_key=self.settings.embed_api_key or "no-key",
                timeout=60,
                max_retries=0,
            )
        return self._client

    # ---- 维度/空向量校验 ----
    def _validate(self, vectors: list[list[float]], expected: int) -> list[list[float]]:
        """校验返回条数、维度与空向量；不合格抛 EmbeddingValidationError（不带病入库）。"""
        if len(vectors) != expected:
            raise EmbeddingValidationError(f"Embedding 返回条数不符：期望 {expected}，实际 {len(vectors)}")
        out: list[list[float]] = []
        for index, vec in enumerate(vectors):
            values = list(map(float, vec))
            if len(values) != self.dim:
                raise EmbeddingValidationError(
                    f"Embedding 维度不符：期望 {self.dim}，实际 {len(values)}（第 {index} 条；"
                    "检查 EMBED_MODEL/EMBED_DIM）"
                )
            if vector_norm(values) == 0.0:
                raise EmbeddingValidationError(f"Embedding 返回空向量（第 {index} 条，norm=0）")
            out.append(values)
        return out

    # ---- 核心接口 ----
    def _embed_batch_remote(self, batch: list[str], stats: EmbedStats) -> list[list[float]]:
        """真实调用一批（batch ≤ EMBED_BATCH_SIZE），带可配置重试（默认 2 次退避）。"""
        client = self._get_client()
        retries = max(0, int(self.settings.embed_retries))
        last_exc: BaseException | None = None
        for attempt in range(retries + 1):
            try:
                response = client.embeddings.create(model=self.settings.embed_model, input=batch)
                vectors = [list(map(float, item.embedding)) for item in response.data]
                return self._validate(vectors, len(batch))
            except EmbeddingError:
                raise
            except Exception as exc:  # noqa: BLE001
                last_exc = exc
                stats.errors += 1
                if attempt < retries:
                    stats.retries += 1
                    time.sleep(0.5 * (2**attempt))
                    continue
                raise EmbeddingError(
                    f"Embedding 调用失败（{type(exc).__name__}）：{str(exc)[:300]}"
                ) from exc
        raise EmbeddingError(f"Embedding 调用失败：{last_exc}")  # pragma: no cover

    def embed_texts(self, texts: list[str], batch_size: int | None = None) -> tuple[list[list[float]], EmbedStats]:
        """批量向量化（LRU + 磁盘缓存 + 重试 + 降级）。

        输入：
            texts      : 待向量化文本
            batch_size : 覆盖批大小（缺省 settings.embed_batch_size，实测 16 可用）
        输出：(向量列表, EmbedStats)

        降级：真实调用重试耗尽且 `EMBED_FALLBACK_MOCK=true` → 该批用 mock 向量补齐，
              标 `degraded=True` / `fallback_mock_texts=N` 并告警；否则抛 EmbeddingError。
        """
        stats = EmbedStats(texts=len(texts), provider=self.provider, dim=self.dim)
        batch_size = max(1, int(batch_size or self.settings.embed_batch_size))
        started = time.perf_counter()

        ordered: list[list[float] | None] = [None] * len(texts)
        pending: list[str] = []
        pending_keys: list[str] = []
        pending_positions: list[int] = []
        for idx, text in enumerate(texts):
            key = _hash_key(self.provider, self.settings.embed_model, text)
            cached = self._cache_get(key)
            if cached is not None:
                stats.cache_hits += 1
                ordered[idx] = cached
                continue
            pending.append(text)
            pending_keys.append(key)
            pending_positions.append(idx)

        if not pending:
            stats.latency_ms = int((time.perf_counter() - started) * 1000)
            return [vec if vec is not None else [] for vec in ordered], stats

        if self.is_mock:
            for text, key, pos in zip(pending, pending_keys, pending_positions):
                vec = _mock_vector(text, self.dim)
                self._cache_put(key, vec)
                ordered[pos] = vec
            stats.batches = 0
            stats.latency_ms = int((time.perf_counter() - started) * 1000)
            return [vec if vec is not None else [] for vec in ordered], stats

        for start in range(0, len(pending), batch_size):
            batch = pending[start : start + batch_size]
            keys = pending_keys[start : start + batch_size]
            positions = pending_positions[start : start + batch_size]
            stats.batches += 1
            try:
                vectors = self._embed_batch_remote(batch, stats)
                stats.api_calls += 1
            except EmbeddingValidationError:
                # 维度/空向量属配置错误：不降级掩盖，直接抛出
                raise
            except EmbeddingError as exc:
                if not self.settings.embed_fallback_mock:
                    raise
                # 明确降级（绝不静默）：该批用确定性 mock 向量继续，并告警
                vectors = [_mock_vector(text, self.dim) for text in batch]
                stats.fallback_mock_texts += len(batch)
                stats.degraded = True
                self.degraded = True
                self._alert(
                    f"Embedding 真实调用失败，已降级 mock 向量 {len(batch)} 条"
                    f"（索引将混入哈希向量，必须重建）：{str(exc)[:200]}"
                )
            for key, vec, pos in zip(keys, vectors, positions):
                self._cache_put(key, vec)
                ordered[pos] = vec

        stats.latency_ms = int((time.perf_counter() - started) * 1000)
        return [vec if vec is not None else [] for vec in ordered], stats

    def embed_query(self, text: str) -> list[float]:
        """单条查询向量（与入库同一模型，保证同向量空间）。"""
        vectors, _ = self.embed_texts([text])
        if not vectors or not vectors[0]:
            raise EmbeddingError("查询向量为空")
        return vectors[0]

    def embed_documents(
        self, texts: list[str], batch_size: int | None = None
    ) -> tuple[list[list[float]], EmbedStats]:
        """批量文档向量（ingest/reindex 用；返回向量与统计）。"""
        return self.embed_texts(texts, batch_size=batch_size)


def get_embedder(settings: Settings, cache_path: Path | None = None) -> Embedder:
    """构造 Embedder。"""
    return Embedder(settings, cache_path=cache_path)
