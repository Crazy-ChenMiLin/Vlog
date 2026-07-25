#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
zhiguang_be 全量 RAG 索引重建脚本
- 用项目 private.pem 自签 RS256 JWT（issuer=zhiguang），过 Spring Security authenticated() 过滤器
- 循环 POST /api/v1/knowposts/{id}/rag/reindex 对全部 public+published 知文重跑 embedding
- 背景：ES 索引已删除，isUpToDate 指纹校验对所有 post 返回 false => 强制重新向量化
- embedding 已切到 NVIDIA nv-embed-v1 (4096 维)
"""
import json, time, base64, uuid, sys
import requests
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives import hashes

BASE = "http://localhost:18181"
PRIV_PEM = r"D:/resume-project/zhiguang_be/src/main/resources/keys/private.pem"
IDS_FILE = r"D:/resume-project/zhiguang_be/scripts/rag-eval/_post_ids.json"
REPORT = r"D:/resume-project/zhiguang_be/scripts/rag-eval/_reindex_report.json"
ISSUER = "zhiguang"

def b64url(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()

def mint_jwt() -> str:
    with open(PRIV_PEM, "rb") as f:
        key = serialization.load_pem_private_key(f.read(), password=None)
    header = {"alg": "RS256", "typ": "JWT"}
    now = int(time.time())
    payload = {
        "iss": ISSUER,
        "sub": "1",
        "uid": 1,
        "token_type": "access",
        "jti": str(uuid.uuid4()),
        "nickname": "reindex-bot",
        "iat": now,
        "exp": now + 21600,  # 6h，防止限流拖慢后 JWT 过期
    }
    signing_input = (b64url(json.dumps(header, separators=(",", ":")).encode())
                     + "." + b64url(json.dumps(payload, separators=(",", ":")).encode())).encode()
    sig = key.sign(signing_input, padding.PKCS1v15(), hashes.SHA256())
    return signing_input.decode() + "." + b64url(sig)

def wait_backend(token):
    for _ in range(120):
        try:
            r = requests.get(f"{BASE}/actuator/health", timeout=3)
            if r.status_code == 200 and "UP" in r.text:
                # 顺便确认鉴权链路通：用真实 token 打一个 reindex 看是否 200/4xx(非401)
                return True
        except Exception:
            pass
        time.sleep(2)
    return False

def main():
    with open(IDS_FILE) as f:
        ids = json.load(f)
    token = mint_jwt()
    headers = {"Authorization": f"Bearer {token}"}
    print(f"[*] 后端就绪探测中...", flush=True)
    if not wait_backend(token):
        print("[!] 后端 120s 内未就绪，退出。先检查启动日志。", flush=True)
        sys.exit(1)
    print(f"[*] 后端就绪，开始重建 {len(ids)} 篇索引", flush=True)

    ok, fail, total_chunks = 0, 0, 0
    failures = []
    for i, pid in enumerate(ids):
        url = f"{BASE}/api/v1/knowposts/{pid}/rag/reindex"
        retry = 0
        while True:
            try:
                r = requests.post(url, headers=headers, timeout=120)
            except Exception as e:
                r = None
                body = str(e)
            if r is not None and r.status_code == 200:
                try:
                    n = int(r.text)
                except Exception:
                    n = -1
                if n >= 0:
                    total_chunks += n
                    ok += 1
                else:
                    fail += 1
                    failures.append((pid, r.status_code, r.text[:200]))
                break
            # 429 / 5xx 重试（指数退避）
            if r is not None and r.status_code in (429, 500, 502, 503, 504):
                retry += 1
                if retry > 6:
                    fail += 1
                    failures.append((pid, r.status_code if r else 0, (r.text if r else body)[:200]))
                    break
                sleep_t = min(2 ** retry, 30)
                time.sleep(sleep_t)
                continue
            # 其它错误（含 401 鉴权失败）
            fail += 1
            failures.append((pid, r.status_code if r else 0, (r.text if r else body)[:200]))
            break
        if (i + 1) % 20 == 0 or i == len(ids) - 1:
            print(f"  progress {i+1}/{len(ids)} ok={ok} fail={fail} chunks={total_chunks}", flush=True)
        time.sleep(0.3)  # 平缓 NVIDIA 调用压力

    report = {"total": len(ids), "ok": ok, "fail": fail, "chunks": total_chunks, "failures": failures}
    with open(REPORT, "w") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print(f"[*] 完成: ok={ok} fail={fail} chunks={total_chunks}", flush=True)
    if failures:
        print("[!] 失败列表(前10):", flush=True)
        for p in failures[:10]:
            print("   ", p, flush=True)

if __name__ == "__main__":
    main()
