import json
import time
import uuid
import urllib.request
from pathlib import Path

import jwt


BASE = "http://localhost:8080"
ROOT = Path(__file__).resolve().parents[1]
PRIVATE_KEY_PATH = ROOT / "src" / "main" / "resources" / "keys" / "private.pem"
INPUT_PATH = ROOT / "target" / "codex-rerank-sectiontype-compare.json"
OUTPUT_PATH = ROOT / "target" / "codex-rerank-sectiontype-reindex.json"
USER_ID = 1


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


def post_reindex(post_id: str) -> int:
    request = urllib.request.Request(
        f"{BASE}/api/v1/knowposts/{post_id}/rag/reindex",
        headers=jwt_headers(),
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=120) as response:
        return int(response.read().decode("utf-8"))


def collect_post_ids() -> list[str]:
    data = json.loads(INPUT_PATH.read_text(encoding="utf-8"))
    post_ids = set()
    for row in data.get("rows", []):
        chunks = []
        for key in ["fusedTop1", "rerankTop1", "baselineRerankTop1"]:
            if row.get(key):
                chunks.append(row[key])
        chunks.extend(row.get("fusedTop5") or [])
        chunks.extend(row.get("rerankedTop5") or [])
        for chunk in chunks:
            post_id = chunk.get("postId") if chunk else None
            if post_id:
                post_ids.add(str(post_id))
    return sorted(post_ids)


def main():
    rows = []
    for post_id in collect_post_ids():
        started = time.perf_counter()
        try:
            chunk_count = post_reindex(post_id)
            rows.append({
                "postId": post_id,
                "chunkCount": chunk_count,
                "latencyMs": round((time.perf_counter() - started) * 1000),
                "error": None,
            })
        except Exception as exc:
            rows.append({
                "postId": post_id,
                "chunkCount": None,
                "latencyMs": round((time.perf_counter() - started) * 1000),
                "error": str(exc),
            })
    result = {
        "reindexedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "total": len(rows),
        "success": sum(1 for row in rows if row["error"] is None),
        "errors": sum(1 for row in rows if row["error"] is not None),
        "rows": rows,
    }
    OUTPUT_PATH.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: result[k] for k in ["reindexedAt", "total", "success", "errors"]}, ensure_ascii=False, indent=2))
    print(str(OUTPUT_PATH))


if __name__ == "__main__":
    main()
