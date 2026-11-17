"""物业 AI 客服 · AI 编排服务骨架（Python 3.11+，零第三方依赖）。

P1 只提供 /healthz 与 /v1/orchestrate 的 mock 响应，证明“模型/编排在 Python”边界；
W3 再接 DeepSeek 官方/中转、知识检索、引用与护栏。

运行：
    python services/ai-orchestrator/main.py
    # 默认 127.0.0.1:4100
"""
from __future__ import annotations

import json
import os
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SERVICE = "property-ai-orchestrator"
PORT = int(os.environ.get("AI_ORCHESTRATOR_PORT", "4100"))


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


class Handler(BaseHTTPRequestHandler):
    server_version = "PropertyAIOrchestrator/0.1"

    def _send(self, status: int, payload: dict) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "http://localhost:5173")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802
        if self.path == "/healthz":
            self._send(200, {"ok": True, "service": SERVICE, "mock": True, "now": now()})
            return
        self._send(404, {"ok": False, "error": "NOT_FOUND", "traceId": ""})

    def do_POST(self) -> None:  # noqa: N802
        if self.path != "/v1/orchestrate":
            self._send(404, {"ok": False, "error": "NOT_FOUND", "traceId": ""})
            return
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length else b"{}"
        try:
            req = json.loads(raw or b"{}")
        except json.JSONDecodeError:
            self._send(400, {"ok": False, "error": "BAD_JSON", "traceId": ""})
            return
        # P1 mock：不调用模型；W3 替换为真实编排/模型网关。
        self._send(200, {
            "ok": True,
            "data": {
                "intent": "knowledge_query",
                "answer": "P1 mock：AI 编排服务已连通，W3 接 DeepSeek/知识检索。",
                "citations": [],
                "model": "mock",
                "degraded": True,
                "traceId": req.get("traceId") or "mock-ai-001",
                "now": now(),
            },
        })

    def log_message(self, fmt: str, *args) -> None:  # noqa: A003
        print(f"[{SERVICE}] {fmt % args}")


if __name__ == "__main__":
    print(f"{SERVICE} listening on http://127.0.0.1:{PORT}")
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
