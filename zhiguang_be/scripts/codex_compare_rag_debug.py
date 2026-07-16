import json
import time
import uuid
import urllib.parse
import urllib.request
from pathlib import Path

import jwt


BASE = "http://localhost:8080"
TOP_K = 10
ROOT = Path(__file__).resolve().parents[1]
BASELINE_PATH = ROOT / "target" / "codex-rerank-title-compare.json"
OUTPUT_PATH = ROOT / "target" / "codex-rerank-sectiontype-compare.json"
PRIVATE_KEY_PATH = ROOT / "src" / "main" / "resources" / "keys" / "private.pem"
USER_ID = 1


QUESTIONS = [
    "RAG 的 rerank 是什么？",
    "RAG 查询改写有什么用？",
    "RAG 的 RRF 是什么？",
    "HyDE 在 RAG 里解决什么问题？",
    "Redis 缓存穿透怎么解决？",
    "Redis 分布式锁要注意什么？",
    "Spring Boot 事务传播是什么？",
    "Java Stream peek 有什么作用？",
    "Elasticsearch 倒排索引是什么？",
    "RAG 切片策略怎么设计？",
]


def request_debug(question: str) -> dict:
    query = urllib.parse.urlencode({"question": question, "topK": TOP_K})
    url = f"{BASE}/api/v1/knowposts/qa/debug?{query}"
    request = urllib.request.Request(url, headers=jwt_headers(), method="GET")
    with urllib.request.urlopen(request, timeout=90) as response:
        return json.loads(response.read().decode("utf-8"))


def jwt_headers() -> dict:
    now = int(time.time())
    token = jwt.encode(
        {
            "sub": str(USER_ID),
            "uid": USER_ID,
            "typ": "access",
            "iat": now,
            "exp": now + 3600,
            "jti": str(uuid.uuid4()),
        },
        PRIVATE_KEY_PATH.read_text(encoding="utf-8"),
        algorithm="RS256",
    )
    return {"Authorization": f"Bearer {token}"}


def chunk_key(chunk: dict | None) -> str | None:
    if not chunk:
        return None
    return chunk.get("chunkId") or f"{chunk.get('postId')}#{chunk.get('position')}"


def topic_hit(question: str, chunk: dict | None) -> bool:
    if not chunk:
        return False
    haystack = f"{chunk.get('title') or ''} {chunk.get('textPreview') or ''}".lower()
    keywords = [
        "rerank",
        "查询改写",
        "rrf",
        "hyde",
        "缓存穿透",
        "分布式锁",
        "事务传播",
        "peek",
        "倒排索引",
        "切片策略",
    ]
    expected = next((keyword for keyword in keywords if keyword.lower() in question.lower()), None)
    return bool(expected and expected.lower() in haystack)


def section_hint(chunk: dict | None) -> str:
    if not chunk:
        return "NONE"
    preview = chunk.get("textPreview") or ""
    if preview.startswith("# "):
        return "TITLE"
    if "## 核心概念" in preview:
        return "CONCEPT"
    if "## 背景" in preview:
        return "BACKGROUND"
    if "## RAG 测试问题" in preview or "测试问题" in preview[:80]:
        return "TEST_QUESTION"
    if "## 面试回答模板" in preview:
        return "INTERVIEW_TEMPLATE"
    if "解决" in preview[:120] or "方案" in preview[:120] or "排查" in preview[:120]:
        return "SOLUTION"
    return "OTHER"


def compact_chunk(chunk: dict | None) -> dict | None:
    if not chunk:
        return None
    return {
        "rank": chunk.get("rank"),
        "postId": chunk.get("postId"),
        "chunkId": chunk.get("chunkId"),
        "title": chunk.get("title"),
        "position": chunk.get("position"),
        "vectorScore": chunk.get("vectorScore"),
        "sectionHint": section_hint(chunk),
        "textPreview": chunk.get("textPreview"),
    }


def main():
    baseline = json.loads(BASELINE_PATH.read_text(encoding="utf-8"))
    baseline_by_question = {row["question"]: row for row in baseline["rows"]}

    rows = []
    errors = []
    for question in QUESTIONS:
        started = time.perf_counter()
        try:
            debug = request_debug(question)
            latency_ms = round((time.perf_counter() - started) * 1000)
            fused = debug.get("fusedResults") or []
            reranked = debug.get("rerankedResults") or []
            base = baseline_by_question.get(question) or {}
            fused_top1 = fused[0] if fused else None
            rerank_top1 = reranked[0] if reranked else None
            base_rerank_top1 = base.get("rerankTop1")

            rows.append({
                "question": question,
                "latencyMs": latency_ms,
                "fusedCount": len(fused),
                "rerankedCount": len(reranked),
                "candidateSetSame": sorted(filter(None, [chunk_key(c) for c in fused]))
                == sorted(filter(None, [chunk_key(c) for c in reranked])),
                "sameTop1WithinCurrentRun": chunk_key(fused_top1) == chunk_key(rerank_top1),
                "sameRerankTop1AsTitleBaseline": chunk_key(rerank_top1) == chunk_key(base_rerank_top1),
                "topicHitFusedTop1": topic_hit(question, fused_top1),
                "topicHitRerankTop1": topic_hit(question, rerank_top1),
                "fusedTop1Section": section_hint(fused_top1),
                "rerankTop1Section": section_hint(rerank_top1),
                "baselineRerankTop1Section": section_hint(base_rerank_top1),
                "fusedTop1": compact_chunk(fused_top1),
                "rerankTop1": compact_chunk(rerank_top1),
                "baselineRerankTop1": compact_chunk(base_rerank_top1),
                "fusedTop5": [compact_chunk(c) for c in fused[:5]],
                "rerankedTop5": [compact_chunk(c) for c in reranked[:5]],
            })
        except Exception as exc:
            errors.append({"question": question, "error": str(exc)})

    summary = {
        "testedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "endpoint": f"GET /api/v1/knowposts/qa/debug?topK={TOP_K}",
        "changeUnderTest": "Rerank passages can include sectionTitle/sectionType when indexed metadata exists",
        "note": "This run does not force a full reindex before testing.",
        "total": len(QUESTIONS),
        "errors": len(errors),
        "candidateSetSameCount": sum(1 for row in rows if row["candidateSetSame"]),
        "top1ChangedWithinCurrentRun": sum(1 for row in rows if not row["sameTop1WithinCurrentRun"]),
        "rerankTop1ChangedVsTitleBaseline": sum(1 for row in rows if not row["sameRerankTop1AsTitleBaseline"]),
        "topicHitRerankTop1": sum(1 for row in rows if row["topicHitRerankTop1"]),
        "badSectionRerankTop1": sum(
            1 for row in rows
            if row["rerankTop1Section"] in {"TITLE", "BACKGROUND", "TEST_QUESTION"}
        ),
        "rows": rows,
        "errorsDetail": errors,
    }
    OUTPUT_PATH.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({
        key: summary[key]
        for key in [
            "testedAt",
            "total",
            "errors",
            "candidateSetSameCount",
            "top1ChangedWithinCurrentRun",
            "rerankTop1ChangedVsTitleBaseline",
            "topicHitRerankTop1",
            "badSectionRerankTop1",
        ]
    }, ensure_ascii=False, indent=2))
    print(str(OUTPUT_PATH))


if __name__ == "__main__":
    main()
