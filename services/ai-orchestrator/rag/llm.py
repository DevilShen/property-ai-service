"""公司中转 LLM 客户端（OpenAI 兼容）
=====================================

职责：统一封装 relay / mock / retrieval_only 三种模式，供重排与生成复用。

接入事实（2026-11-16 实测，记录于 `_dev/reports/RAG-W30-PoC.md`）：
    - `LLM_BASE_URL=https://relay.example.com/v1`（**必须带 /v1**，SDK 只追加 /chat/completions）；
    - `deepseek-v4-flash` / `deepseek-v4-pro` 无指定 UA → **403 `agent_not_allowed`**
      （中转站按客户端白名单限制，需公司 IT 授权；本模块**不伪装 UA**）；
    - `k3` / `kimi-for-coding` 无需 UA 可 200，但属**推理型**：
      `completion_tokens` 含 `reasoning_tokens`，`max_tokens` 给小会 `finish_reason=length`
      且正文为空 → 本模块识别后抛可读错误；
    - 同一批推理模型只接受 `temperature=1`（传 0 → 400 `invalid temperature`），
      本模块自动降级为「不传 temperature」并在结果里标 `temperature_fallback=True`。

安全：日志/异常只记模型名、耗时、token 与状态码，**不记 Prompt 与密钥**。
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Any

from .config import Settings, mask_secret


class LLMError(RuntimeError):
    """LLM 调用失败基类（消息可直接展示给开发者，不含密钥/Prompt）。"""


class LLMUnavailableError(LLMError):
    """当前模式不可真实调用（mock / retrieval_only / 无 Key）。"""


class LLMBlockedError(LLMError):
    """中转站拒绝（403 agent_not_allowed / 权限不足）。"""


class LLMEmptyContentError(LLMError):
    """推理型模型 token 预算不足：`finish_reason=length` 且正文为空。"""


class LLMTimeoutError(LLMError):
    """调用超时（含重试后仍超时）。"""


@dataclass
class LLMResult:
    """一次补全的返回值。"""

    text: str
    model: str
    usage: dict[str, Any] = field(default_factory=dict)
    finish_reason: str = ""
    latency_ms: int = 0
    temperature_fallback: bool = False
    reasoning_chars: int = 0

    @property
    def prompt_tokens(self) -> int:
        return int(self.usage.get("prompt_tokens") or 0)

    @property
    def completion_tokens(self) -> int:
        return int(self.usage.get("completion_tokens") or 0)

    @property
    def total_tokens(self) -> int:
        return int(self.usage.get("total_tokens") or (self.prompt_tokens + self.completion_tokens))

    def to_dict(self) -> dict[str, Any]:
        return {
            "model": self.model,
            "finish_reason": self.finish_reason,
            "latency_ms": self.latency_ms,
            "usage": self.usage,
            "temperature_fallback": self.temperature_fallback,
            "reasoning_chars": self.reasoning_chars,
        }


# 可重试的瞬时错误（超时/连接/限流/上游 5xx）
def _status_of(exc: BaseException) -> int | None:
    """取 HTTP 状态码并归一为 int（不同 SDK 版本可能给 str/int/仅 response）。"""
    raw = getattr(exc, "status_code", None)
    if raw is None:
        response = getattr(exc, "response", None)
        raw = getattr(response, "status_code", None)
    try:
        return int(raw)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return None


def _is_retryable(exc: BaseException) -> bool:
    name = type(exc).__name__
    if name in {"APITimeoutError", "APIConnectionError", "RateLimitError", "InternalServerError"}:
        return True
    return _status_of(exc) in {429, 500, 502, 503, 504}


class LLMClient:
    """OpenAI 兼容客户端（公司中转站）。

    输入：
        settings : 配置（base_url/model/key/timeout 预算）
        model    : 覆盖模型名（重排可用便宜模型）
        user_agent: 显式 UA（默认空 = 不伪装；白名单需公司 IT 授权）
    """

    def __init__(self, settings: Settings, model: str | None = None, user_agent: str | None = None) -> None:
        self.settings = settings
        self.model = (model or settings.llm_model).strip() or settings.llm_model
        # 合规：默认空 UA，绝不从参考工程 .env 复制伪装 UA
        self.user_agent = (user_agent if user_agent is not None else settings.llm_user_agent) or ""
        self._client = None

    # ---- 模式判定 ----
    @property
    def is_mock(self) -> bool:
        return self.settings.llm_provider == "mock"

    @property
    def is_retrieval_only(self) -> bool:
        return self.settings.llm_provider == "retrieval_only" or not self.settings.llm_api_key

    @property
    def can_call(self) -> bool:
        """是否具备真实调用条件（relay + 有 Key）。"""
        return not self.is_mock and not self.is_retrieval_only

    def _get_client(self, timeout_s: float):
        """惰性创建 SDK 客户端（每次调用单独设置超时，禁 SDK 内部重试）。"""
        if self._client is None:
            try:
                from openai import OpenAI
            except Exception as exc:  # pragma: no cover - 依赖缺失
                raise LLMError("缺少 openai 依赖，无法调用中转站") from exc
            headers: dict[str, str] = {}
            if self.user_agent:
                headers["User-Agent"] = self.user_agent
            self._client = OpenAI(
                base_url=self.settings.llm_base_url,
                api_key=self.settings.llm_api_key or "no-key",
                timeout=timeout_s,
                max_retries=0,
                default_headers=headers or None,
            )
        else:
            # 超时需按调用变化：SDK 支持对单次请求传 timeout，见 complete()
            pass
        return self._client

    # ---- 补全 ----
    def complete(
        self,
        messages: list[dict[str, str]],
        *,
        temperature: float | None = None,
        max_tokens: int | None = None,
        model: str | None = None,
        timeout_ms: int | None = None,
        retries: int = 2,
    ) -> LLMResult:
        """调用 `/chat/completions`。

        输入：
            messages    : OpenAI 格式消息
            temperature : 温度；None → 不传（推理型模型只接受 1 或省略）
            max_tokens  : 输出上限；缺省 settings.llm_max_tokens
            model       : 模型覆盖；缺省 self.model
            timeout_ms  : 单次请求超时；缺省 60s
            retries     : 瞬时错误重试次数（指数退避 0.5s/1s/2s...）
        输出：LLMResult
        异常：LLMUnavailableError / LLMBlockedError / LLMEmptyContentError / LLMTimeoutError / LLMError
        """
        if not self.can_call:
            raise LLMUnavailableError(
                f"LLM 当前不可调用（provider={self.settings.llm_provider}, 有Key={bool(self.settings.llm_api_key)}）"
            )

        model_name = (model or self.model).strip() or self.model
        max_tokens = int(max_tokens or self.settings.llm_max_tokens)
        timeout_s = max((timeout_ms or 60000) / 1000.0, 0.5)
        client = self._get_client(timeout_s)

        last_exc: BaseException | None = None
        temperature_fallback = False
        attempt = 0
        # 注意：temperature 回退不消耗重试次数（中转站推理模型只允许 temperature=1 或省略），
        # 因此用 while True + 显式 raise 控制终止，避免 retries=0 时回退被循环条件挡掉。
        while True:
            kwargs: dict[str, Any] = {
                "model": model_name,
                "messages": messages,
                "max_tokens": max_tokens,
                "timeout": timeout_s,
            }
            if temperature is not None and not temperature_fallback:
                kwargs["temperature"] = temperature
            started = time.perf_counter()
            try:
                response = client.chat.completions.create(**kwargs)
                latency_ms = int((time.perf_counter() - started) * 1000)
                choice = response.choices[0]
                message = choice.message
                content = (message.content or "").strip()
                reasoning = getattr(message, "reasoning_content", None) or ""
                usage = response.usage.model_dump() if response.usage is not None else {}
                finish = choice.finish_reason or ""

                # 推理型模型：reasoning 吃光 max_tokens → 正文为空，抛可读错误便于降级
                if not content:
                    reasoning_tokens = int((usage.get("completion_tokens_details") or {}).get("reasoning_tokens") or 0)
                    if finish == "length":
                        raise LLMEmptyContentError(
                            f"模型 {model_name} 正文为空：finish_reason=length，"
                            f"reasoning_tokens={reasoning_tokens}，max_tokens={max_tokens} 不足"
                            "（推理型模型需 ≥1024）"
                        )
                    raise LLMEmptyContentError(f"模型 {model_name} 返回空正文（finish_reason={finish}）")

                return LLMResult(
                    text=content,
                    model=model_name,
                    usage=usage,
                    finish_reason=finish,
                    latency_ms=latency_ms,
                    temperature_fallback=temperature_fallback,
                    reasoning_chars=len(reasoning),
                )
            except LLMEmptyContentError:
                raise
            except Exception as exc:  # noqa: BLE001 - 分类后重抛
                last_exc = exc
                name = type(exc).__name__
                status = _status_of(exc)
                detail = _scrub(str(exc), self.settings.llm_api_key)[:400]

                # 1) 模型只接受 temperature=1：去掉 temperature 重试一次（如实标记降级）
                if status == 400 and "temperature" in detail.lower() and not temperature_fallback:
                    temperature_fallback = True
                    attempt += 1
                    continue

                # 2) 403：白名单/权限，重试无意义 → 可读错误
                if status == 403:
                    raise LLMBlockedError(
                        f"模型 {model_name} 被中转站拒绝（403 agent_not_allowed/permission）："
                        f"{detail}；处置：由公司 IT 白名单授权，或改用无需 UA 的模型（k3/kimi-for-coding）"
                    ) from exc

                # 3) 400 其它参数错误：直接抛（重试无意义）
                if status == 400:
                    raise LLMError(f"模型 {model_name} 参数错误（400）：{detail}") from exc

                # 4) 瞬时错误：指数退避重试
                if attempt < retries and _is_retryable(exc):
                    time.sleep(0.5 * (2**attempt))
                    attempt += 1
                    continue

                if name == "APITimeoutError" or isinstance(exc, TimeoutError):
                    raise LLMTimeoutError(f"模型 {model_name} 调用超时（>{timeout_s:.1f}s）") from exc
                raise LLMError(f"模型 {model_name} 调用失败（{name}）：{detail}") from exc
        raise LLMError(f"模型 {model_name} 调用失败：{_scrub(str(last_exc), self.settings.llm_api_key)}")


def _scrub(text: str, api_key: str | None) -> str:
    """从错误文本中抹掉可能的密钥（防止 SDK 异常把 Authorization 带出）。"""
    text = text or ""
    if api_key:
        text = text.replace(api_key, mask_secret(api_key))
    return text


def get_llm(settings: Settings, model: str | None = None) -> LLMClient:
    """构造 LLM 客户端（不做单例：PoC 需要不同模型/超时并存）。"""
    return LLMClient(settings, model=model)


def probe_models(settings: Settings, models: list[str], timeout_ms: int = 20000) -> list[dict[str, Any]]:
    """逐个探测模型可用性（给报告的 403 证据用，不写密钥）。

    输入：
        settings : 配置
        models   : 待探测模型名
        timeout_ms: 单次探测超时
    输出：[{model, ok, error, latency_ms, usage?, note}]
    """
    results: list[dict[str, Any]] = []
    for name in models:
        client = LLMClient(settings, model=name)
        try:
            result = client.complete(
                [{"role": "user", "content": "请只回复：OK"}],
                max_tokens=64,
                timeout_ms=timeout_ms,
                retries=0,
            )
            results.append(
                {
                    "model": name,
                    "ok": True,
                    "error": "",
                    "latency_ms": result.latency_ms,
                    "usage": result.usage,
                    "temperature_fallback": result.temperature_fallback,
                    "note": "可用（无 UA）",
                }
            )
        except LLMBlockedError as exc:
            results.append({"model": name, "ok": False, "error": "403 agent_not_allowed", "detail": str(exc)[:300], "latency_ms": None, "note": "需公司 IT 白名单授权"})
        except LLMEmptyContentError as exc:
            results.append({"model": name, "ok": False, "error": "empty_content", "detail": str(exc)[:300], "latency_ms": None, "note": "推理型模型 token 预算不足"})
        except Exception as exc:  # noqa: BLE001 - 探测不因单模型失败中断
            results.append({"model": name, "ok": False, "error": type(exc).__name__, "detail": str(exc)[:300], "latency_ms": None, "note": ""})
    return results
