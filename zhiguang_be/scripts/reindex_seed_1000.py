"""可选：把 seed_1000_posts.py 新生成的【知光精选】文章做 RAG 向量索引。

前置条件：
  - 本地后端已启动（例如 mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=18181）
  - 后端能连到 100.83.242.114 上的 ES / MinIO / embedding 网关
  - 本脚本只做「切片 + 向量化 + 写 ES」，不改任何业务数据

用法：
  RAG_SEED_BASE=http://localhost:18181 SEED_STATE=target/seed1000/state.json \
    python scripts/reindex_seed_1000.py

特性：
  - 并发 reindex（默认 8 线程），带重试
  - 从 state.json 读取本次新增的 id 列表，只索引新文章
  - 输出 target/seed1000/reindex-report.json
"""
import json
import os
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from urllib.parse import urljoin

import jwt
import requests
import yaml

ROOT = Path(__file__).resolve().parents[1]
BASE = os.getenv("RAG_SEED_BASE", "http://localhost:18181")
STATE_FILE = Path(os.getenv("SEED_STATE", "target/seed1000/state.json"))
USER_ID = 1
WORKERS = int(os.getenv("SEED_WORKERS", "8"))


def jwt_headers():
    private_key = (ROOT / "src/main/resources/keys/private.pem").read_text(encoding="utf-8")
    now = int(time.time())
    token = jwt.encode({"sub": str(USER_ID), "uid": USER_ID, "typ": "access", "iat": now,
                        "exp": now + 3600, "jti": __import__("uuid").uuid4().hex},
                       private_key, algorithm="RS256")
    return {"Authorization": f"Bearer {token}"}


def reindex_one(post_id, headers):
    for attempt in range(4):
        try:
            r = requests.post(urljoin(BASE, f"/api/v1/knowposts/{post_id}/rag/reindex"),
                              headers=headers, timeout=120)
            if r.status_code >= 400:
                raise RuntimeError(f"{r.status_code}: {r.text[:200]}")
            return int(r.text.strip())
        except Exception:
            if attempt == 3:
                raise
            time.sleep(3)


def main():
    if not STATE_FILE.exists():
        raise SystemExit(f"找不到 {STATE_FILE}，请先跑 seed_1000_posts.py")
    ids = json.loads(STATE_FILE.read_text(encoding="utf-8")).get("created", [])
    if not ids:
        print("state 里没有新增 id，无需索引。")
        return
    headers = jwt_headers()
    print(f"准备 reindex {len(ids)} 篇，并发={WORKERS}，后端={BASE}", flush=True)

    ok, fail = 0, 0
    report = {"base": BASE, "total": len(ids), "ok": [], "fail": []}
    start = time.time()
    with ThreadPoolExecutor(max_workers=WORKERS) as ex:
        futs = {ex.submit(reindex_one, pid, headers): pid for pid in ids}
        done = 0
        for fut in as_completed(futs):
            pid = futs[fut]
            done += 1
            try:
                chunks = fut.result()
                ok += 1
                report["ok"].append({"id": pid, "chunks": chunks})
            except Exception as e:
                fail += 1
                report["fail"].append({"id": pid, "error": str(e)[:200]})
            if done % 50 == 0 or done == len(ids):
                print(f"[{done}/{len(ids)}] ok={ok} fail={fail} elapsed={int(time.time()-start)}s", flush=True)

    report["elapsedSec"] = int(time.time() - start)
    out = ROOT / "target/seed1000/reindex-report.json"
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"ok": ok, "fail": fail, "elapsedSec": report["elapsedSec"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
