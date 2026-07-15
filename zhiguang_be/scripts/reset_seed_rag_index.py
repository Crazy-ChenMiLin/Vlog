import json
import os
import time

import requests

import repair_seed_rag_docs as seed


BASE = os.getenv("RAG_SEED_BASE", seed.BASE)


def bulk_delete(config: dict, ids: list[str]):
    response = requests.post(
        f"{config['es_url']}/{config['es_index']}/_delete_by_query?conflicts=proceed&refresh=true",
        auth=(config["es_user"], config["es_password"]),
        headers={"Content-Type": "application/json"},
        data=json.dumps({"query": {"terms": {"metadata.postId": ids}}}, ensure_ascii=False).encode("utf-8"),
        timeout=120,
    )
    if response.status_code >= 400:
        raise RuntimeError(f"bulk delete failed: {response.status_code} {response.text[:1000]}")
    print("bulk_delete", response.text)


def api(method: str, path: str, headers: dict, **kwargs):
    response = requests.request(method, seed.urljoin(BASE, path), headers=headers, timeout=120, **kwargs)
    if response.status_code >= 400:
        raise RuntimeError(f"{method} {path} -> {response.status_code}: {response.text[:500]}")
    return response


def main():
    config = seed.load_config()
    headers = seed.jwt_headers()
    ids = seed.find_seed_posts(config)
    if len(ids) != 100:
        raise RuntimeError(f"expected 100 generated posts, found {len(ids)}")

    bulk_delete(config, ids)
    time.sleep(2)

    total = 0
    for index, post_id in enumerate(ids, start=1):
        chunks_text = api("POST", f"/api/v1/knowposts/{post_id}/rag/reindex", headers).text.strip()
        chunks = int(chunks_text)
        total += chunks
        print(f"reindexed {index:03d}/100 {post_id} chunks={chunks}")

    print(json.dumps({"reindexed": len(ids), "chunks": total, "base": BASE}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
