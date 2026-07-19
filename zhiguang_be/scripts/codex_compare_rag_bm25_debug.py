import json
import os
import time
import uuid
import urllib.parse
import urllib.request
from pathlib import Path

import jwt


BASE = os.environ.get("RAG_BASE", "http://localhost:8080")
TOP_K = 10
ROOT = Path(__file__).resolve().parents[1]
OUTPUT_PATH = Path(os.environ.get(
    "RAG_BM25_OUTPUT_PATH",
    ROOT / "target" / "codex-rag-bm25-hybrid-compare.json",
))
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
    "RAG 召回评估应该看哪些指标？",
    "RAG 上下文压缩有什么用？",
    "向量库在 RAG 里负责什么？",
    "引用溯源在 RAG 里为什么重要？",
    "Redis 布隆过滤器解决什么问题？",
    "Redis 缓存击穿怎么解决？",
    "Kafka 消费者组是什么？",
    "MySQL 的 MVCC 是什么？",
    "Spring Boot AOP 有什么用？",
    "JWT 刷新令牌为什么需要 jti？",
    "BM25 在 RAG 召回里适合解决什么问题？",
    "向量检索和关键词检索有什么区别？",
    "RAG 混合召回为什么需要 RRF？",
    "RAG 为什么需要对 chunk 做 rerank？",
    "RAG 的 chunk 太长会有什么问题？",
    "RAG 的 chunk 太短会有什么问题？",
    "HyDE 和 query rewrite 有什么区别？",
    "Elasticsearch 的 BM25 是怎么打分的？",
    "Elasticsearch function_score 有什么作用？",
    "Elasticsearch multi_match 适合什么场景？",
    "Redis 缓存雪崩怎么解决？",
    "Redis 热 key 怎么治理？",
    "Redis 过期策略有哪些？",
    "Kafka 如何保证消息不丢失？",
    "Kafka 分区有什么作用？",
    "MySQL 索引失效有哪些常见情况？",
    "MySQL 事务隔离级别有哪些？",
    "Spring Boot 自动配置是什么？",
    "Spring Bean 生命周期是什么？",
    "JWT access token 和 refresh token 怎么配合？",
]


def request_debug(question: str) -> dict:
    query = urllib.parse.urlencode({"question": question, "topK": TOP_K})
    request = urllib.request.Request(
        f"{BASE}/api/v1/knowposts/qa/debug?{query}",
        headers=jwt_headers(),
        method="GET",
    )
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


def key_set(chunks: list[dict]) -> set[str]:
    return {key for key in (chunk_key(chunk) for chunk in chunks) if key}


def main():
    rows = []
    errors = []
    for question in QUESTIONS:
        started = time.perf_counter()
        try:
            debug = request_debug(question)
            latency_ms = round((time.perf_counter() - started) * 1000)
            original = debug.get("originalResults") or []
            hyde = debug.get("hydeResults") or []
            keyword = debug.get("keywordResults") or []
            fused = debug.get("fusedResults") or []
            reranked = debug.get("rerankedResults") or []

            keyword_keys = key_set(keyword)
            vector_keys = key_set(original) | key_set(hyde)
            keyword_only_keys = keyword_keys - vector_keys
            fused_keys = key_set(fused)
            reranked_top5_keys = key_set(reranked[:5])

            rows.append({
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
            })
        except Exception as exc:
            errors.append({"question": question, "error": str(exc)})

    summary = {
        "testedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "endpoint": f"GET /api/v1/knowposts/qa/debug?topK={TOP_K}",
        "changeUnderTest": "Hybrid retrieval: original vector + HyDE vector + BM25 keyword recall + RRF + rerank",
        "total": len(QUESTIONS),
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
            "keywordReturnedCount",
            "keywordOnlyCandidateTotal",
            "keywordCandidatesInFusedTotal",
            "keywordOnlyInFusedTotal",
            "keywordOnlyInRerankedTop5Total",
            "keywordOnlyInRerankedTop5QuestionCount",
            "fusedTop1FromKeywordCount",
            "rerankTop1FromKeywordCount",
        ]
    }, ensure_ascii=False, indent=2))
    print(str(OUTPUT_PATH))


if __name__ == "__main__":
    main()
