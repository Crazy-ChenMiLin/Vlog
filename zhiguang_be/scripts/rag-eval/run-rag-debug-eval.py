import argparse
import json
import time
import socket
import urllib.parse
import urllib.request
import urllib.error
import uuid
from pathlib import Path

import jwt


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_PRIVATE_KEY = ROOT / "src" / "main" / "resources" / "keys" / "private.pem"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run RAG debug evaluation against one backend instance.")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--questions", type=Path, default=Path(__file__).with_name("questions-40.json"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--top-k", type=int, default=10)
    parser.add_argument("--user-id", type=int, default=1)
    parser.add_argument("--private-key", type=Path, default=DEFAULT_PRIVATE_KEY)
    parser.add_argument("--label", default="rag-debug")
    parser.add_argument("--retries", type=int, default=2)
    parser.add_argument("--retry-delay", type=float, default=2.0)
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


def request_debug_once(base_url: str, question: str, top_k: int, headers: dict[str, str]) -> dict:
    query = urllib.parse.urlencode({"question": question, "topK": top_k})
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}/api/v1/knowposts/qa/debug?{query}",
        headers=headers,
        method="GET",
    )
    with urllib.request.urlopen(request, timeout=90) as response:
        return json.loads(response.read().decode("utf-8"))


def request_debug(base_url: str, question: str, top_k: int, headers: dict[str, str], retries: int, retry_delay: float) -> dict:
    last_exc = None
    for attempt in range(retries + 1):
        try:
            return request_debug_once(base_url, question, top_k, headers)
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


def chunk_key(chunk: dict | None) -> str | None:
    if not chunk:
        return None
    return chunk.get("chunkId") or f"{chunk.get('postId')}#{chunk.get('position')}"


def key_set(chunks: list[dict]) -> set[str]:
    return {key for key in (chunk_key(chunk) for chunk in chunks) if key}


def compact_chunk(chunk: dict | None) -> dict | None:
    if not chunk:
        return None
    return {
        "rank": chunk.get("rank"),
        "postId": chunk.get("postId"),
        "chunkId": chunk.get("chunkId"),
        "title": chunk.get("title"),
        "position": chunk.get("position"),
        "retrievalScore": chunk.get("vectorScore"),
        "sectionTitle": chunk.get("sectionTitle"),
        "sectionType": chunk.get("sectionType"),
        "questionIntent": chunk.get("questionIntent"),
        "rerankScore": chunk.get("rerankScore"),
        "sectionBoost": chunk.get("sectionBoost"),
        "finalScore": chunk.get("finalScore"),
        "textPreview": chunk.get("textPreview"),
    }


def compact_graph_context(gc: dict | None) -> dict | None:
    """把 debug 接口返回的 graphContext 原样收敛成可落盘的结构。

    这是 A/B 评测的“观察仪表盘”字段：graph-off 时后端返回空对象（matched/relations
    均为空），graph-on 时返回 Neo4j 匹配到的实体/关系/父概念/扩展词。逐题保留它，
    才能复盘每一题到底有没有命中图关系，而不是只看到最终的召回指标。
    """
    if not gc:
        return None
    return {
        "matchedEntities": [
            {"name": m.get("name"), "type": m.get("type")}
            for m in (gc.get("matchedEntities") or [])
        ],
        "relations": [
            {
                "source": r.get("source"),
                "type": r.get("type"),
                "target": r.get("target"),
                "description": r.get("description"),
            }
            for r in (gc.get("relations") or [])
        ],
        "parentConcepts": gc.get("parentConcepts") or [],
        "expandedTerms": gc.get("expandedTerms") or [],
    }


def build_row(question: str, debug: dict, latency_ms: int) -> dict:
    original = debug.get("originalResults") or []
    hyde = debug.get("hydeResults") or []
    keyword = debug.get("keywordResults") or []
    fused = debug.get("fusedResults") or []
    reranked = debug.get("rerankedResults") or []
    gc = compact_graph_context(debug.get("graphContext"))

    keyword_keys = key_set(keyword)
    vector_keys = key_set(original) | key_set(hyde)
    keyword_only_keys = keyword_keys - vector_keys
    fused_keys = key_set(fused)
    reranked_top5_keys = key_set(reranked[:5])

    return {
        "question": question,
        "latencyMs": latency_ms,
        "originalCount": len(original),
        "hydeCount": len(hyde),
        "keywordCount": len(keyword),
        "fusedCount": len(fused),
        "rerankedCount": len(reranked),
        "keywordOnlyCandidateCount": len(keyword_only_keys),
        "keywordCandidatesInFusedCount": len(keyword_keys & fused_keys),
        "keywordOnlyInFusedCount": len(keyword_only_keys & fused_keys),
        "keywordOnlyInRerankedTop5Count": len(keyword_only_keys & reranked_top5_keys),
        "fusedTop1FromKeyword": chunk_key(fused[0]) in keyword_keys if fused else False,
        "rerankTop1FromKeyword": chunk_key(reranked[0]) in keyword_keys if reranked else False,
        "originalTop3": [compact_chunk(chunk) for chunk in original[:3]],
        "hydeTop3": [compact_chunk(chunk) for chunk in hyde[:3]],
        "keywordTop3": [compact_chunk(chunk) for chunk in keyword[:3]],
        "keywordOnlyTop5": [
            compact_chunk(chunk)
            for chunk in keyword
            if chunk_key(chunk) in keyword_only_keys
        ][:5],
        "keywordOnlyInRerankedTop5": [
            compact_chunk(chunk)
            for chunk in reranked[:5]
            if chunk_key(chunk) in keyword_only_keys
        ],
        "fusedTop5": [compact_chunk(chunk) for chunk in fused[:5]],
        "rerankedTop5": [compact_chunk(chunk) for chunk in reranked[:5]],
        # Top10 快照：扩展评测用（判断“召回到但排得靠后”还是“根本没召回”）。
        # 接口返回的 fused/reranked 即为完整融合池（topK=10 时即 10 条），这里直接存前 10。
        "fusedTop10": [compact_chunk(chunk) for chunk in fused[:10]],
        "rerankedTop10": [compact_chunk(chunk) for chunk in reranked[:10]],
        # GraphContext 仪表盘：graph-off 时为 null / 空，graph-on 时带实体与关系
        "graphContext": gc,
        "graphContextHit": bool(gc and (gc.get("matchedEntities") or gc.get("relations"))),
    }


def main() -> None:
    args = parse_args()
    questions = json.loads(args.questions.read_text(encoding="utf-8"))
    headers = jwt_headers(args.private_key, args.user_id)

    rows = []
    errors = []
    for question in questions:
        started = time.perf_counter()
        try:
            debug = request_debug(args.base_url, question, args.top_k, headers, args.retries, args.retry_delay)
            rows.append(build_row(question, debug, round((time.perf_counter() - started) * 1000)))
        except Exception as exc:
            error = {"question": question, "error": str(exc)}
            error.update(format_error(exc))
            errors.append(error)

    summary = {
        "label": args.label,
        "testedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "baseUrl": args.base_url,
        "endpoint": f"GET /api/v1/knowposts/qa/debug?topK={args.top_k}",
        "questionsFile": str(args.questions),
        "total": len(questions),
        "errors": len(errors),
        "keywordReturnedCount": sum(1 for row in rows if row["keywordCount"] > 0),
        "keywordOnlyCandidateTotal": sum(row["keywordOnlyCandidateCount"] for row in rows),
        "keywordCandidatesInFusedTotal": sum(row["keywordCandidatesInFusedCount"] for row in rows),
        "keywordOnlyInFusedTotal": sum(row["keywordOnlyInFusedCount"] for row in rows),
        "keywordOnlyInRerankedTop5Total": sum(row["keywordOnlyInRerankedTop5Count"] for row in rows),
        "keywordOnlyInRerankedTop5QuestionCount": sum(
            1 for row in rows if row["keywordOnlyInRerankedTop5Count"] > 0
        ),
        "fusedTop1FromKeywordCount": sum(1 for row in rows if row["fusedTop1FromKeyword"]),
        "rerankTop1FromKeywordCount": sum(1 for row in rows if row["rerankTop1FromKeyword"]),
        "graphContextHitCount": sum(1 for row in rows if row["graphContextHit"]),
        "graphContextHitQuestionCount": sum(
            1 for row in rows if row.get("graphContextHit")
        ),
        "rows": rows,
        "errorsDetail": errors,
    }

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({key: summary[key] for key in [
        "label",
        "testedAt",
        "total",
        "errors",
        "keywordReturnedCount",
        "keywordOnlyCandidateTotal",
        "keywordOnlyInFusedTotal",
        "keywordOnlyInRerankedTop5Total",
        "keywordOnlyInRerankedTop5QuestionCount",
        "graphContextHitCount",
        "graphContextHitQuestionCount",
    ]}, ensure_ascii=False, indent=2))
    print(str(args.output))


if __name__ == "__main__":
    main()
