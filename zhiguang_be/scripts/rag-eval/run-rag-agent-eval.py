import argparse
import json
import socket
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

import jwt


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_PRIVATE_KEY = ROOT / "src" / "main" / "resources" / "keys" / "private.pem"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run full RAG Agent evaluation against the real QA stream endpoint.")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--questions", type=Path, default=Path(__file__).with_name("questions-relation-cache-100.json"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--user-id", type=int, default=1)
    parser.add_argument("--private-key", type=Path, default=DEFAULT_PRIVATE_KEY)
    parser.add_argument("--label", default="rag-agent")
    parser.add_argument("--eval-run-id", default="")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--sleep", type=float, default=0.0)
    parser.add_argument("--retries", type=int, default=1)
    parser.add_argument("--retry-delay", type=float, default=2.0)
    parser.add_argument("--post-id", type=int, default=0, help="Use post-scoped QA when provided.")
    return parser.parse_args()


def jwt_headers(private_key: Path, user_id: int) -> dict[str, str]:
    now = int(time.time())
    token = jwt.encode(
        {
            "sub": str(user_id),
            "uid": user_id,
            "typ": "access",
            "iat": now,
            "exp": now + 3600,
            "jti": str(uuid.uuid4()),
        },
        private_key.read_text(encoding="utf-8"),
        algorithm="RS256",
    )
    return {"Authorization": f"Bearer {token}"}


def request_agent_once(
        base_url: str,
        question: str,
        top_k: int,
        eval_run_id: str,
        post_id: int,
        headers: dict[str, str]) -> str:
    query = urllib.parse.urlencode({
        "question": question,
        "topK": top_k,
        "evalRunId": eval_run_id,
    })
    path = f"/api/v1/knowposts/{post_id}/qa/stream" if post_id else "/api/v1/knowposts/qa/stream"
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}{path}?{query}",
        headers=headers,
        method="GET",
    )
    with urllib.request.urlopen(request, timeout=180) as response:
        return response.read().decode("utf-8", errors="replace")


def request_agent(
        base_url: str,
        question: str,
        top_k: int,
        eval_run_id: str,
        post_id: int,
        headers: dict[str, str],
        retries: int,
        retry_delay: float) -> str:
    last_exc = None
    for attempt in range(retries + 1):
        try:
            return request_agent_once(base_url, question, top_k, eval_run_id, post_id, headers)
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, socket.timeout) as exc:
            last_exc = exc
            if attempt >= retries:
                raise
            time.sleep(retry_delay * (attempt + 1))
    raise last_exc


def format_error(exc: Exception) -> dict:
    if isinstance(exc, urllib.error.HTTPError):
        body = ""
        try:
            body = exc.read().decode("utf-8", errors="replace")
        except Exception:
            body = ""
        return {
            "type": exc.__class__.__name__,
            "status": exc.code,
            "reason": exc.reason,
            "body": body[:1000],
        }
    if isinstance(exc, urllib.error.URLError):
        return {
            "type": exc.__class__.__name__,
            "reason": str(exc.reason),
        }
    return {
        "type": exc.__class__.__name__,
        "message": str(exc),
    }


def normalize_stream_text(raw: str) -> str:
    lines = []
    for line in raw.splitlines():
        if line.startswith("data:"):
            lines.append(line.removeprefix("data:").strip())
        elif line and not line.startswith("event:") and not line.startswith("id:") and not line.startswith("retry:"):
            lines.append(line)
    return "".join(lines).strip() if lines else raw.strip()


def main() -> None:
    args = parse_args()
    questions = json.loads(args.questions.read_text(encoding="utf-8"))
    if args.limit > 0:
        questions = questions[:args.limit]

    eval_run_id = args.eval_run_id.strip() or f"{args.label}-{time.strftime('%Y%m%d-%H%M%S')}"
    headers = jwt_headers(args.private_key, args.user_id)
    rows = []
    errors = []

    for index, question in enumerate(questions, start=1):
        started = time.perf_counter()
        try:
            raw = request_agent(
                args.base_url,
                question,
                args.top_k,
                eval_run_id,
                args.post_id,
                headers,
                args.retries,
                args.retry_delay,
            )
            answer = normalize_stream_text(raw)
            rows.append({
                "index": index,
                "question": question,
                "ok": True,
                "latencyMs": round((time.perf_counter() - started) * 1000),
                "answerLength": len(answer),
                "answerPreview": answer[:500],
            })
        except Exception as exc:
            error = {
                "index": index,
                "question": question,
                "ok": False,
                "latencyMs": round((time.perf_counter() - started) * 1000),
                "error": str(exc),
            }
            error.update(format_error(exc))
            rows.append(error)
            errors.append(error)

        if args.sleep > 0 and index < len(questions):
            time.sleep(args.sleep)

    summary = {
        "label": args.label,
        "evalRunId": eval_run_id,
        "testedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "baseUrl": args.base_url,
        "endpoint": (
            f"GET /api/v1/knowposts/{args.post_id}/qa/stream?topK={args.top_k}"
            if args.post_id
            else f"GET /api/v1/knowposts/qa/stream?topK={args.top_k}"
        ),
        "questionsFile": str(args.questions),
        "total": len(questions),
        "success": len(rows) - len(errors),
        "errors": len(errors),
        "elkIndexPattern": "zhiguang-agent-observability-*",
        "elkKql": f'event_type: rag_agent_step and eval_run_id: "{eval_run_id}"',
        "rows": rows,
        "errorsDetail": errors,
    }

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({
        "label": summary["label"],
        "evalRunId": summary["evalRunId"],
        "total": summary["total"],
        "success": summary["success"],
        "errors": summary["errors"],
        "elkKql": summary["elkKql"],
        "output": str(args.output),
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
