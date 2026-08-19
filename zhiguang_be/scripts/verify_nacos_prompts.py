#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import json
import urllib.request
import urllib.parse

BASE = "http://100.83.242.114:8848/nacos"


def get(path, params):
    q = urllib.parse.urlencode(params)
    with urllib.request.urlopen(BASE + path + "?" + q, timeout=15) as resp:
        return json.loads(resp.read().decode("utf-8"))


def post(path, params):
    data = urllib.parse.urlencode(params).encode("utf-8")
    req = urllib.request.Request(BASE + path, data=data)
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read().decode("utf-8"))


token = post("/v1/auth/login", {"username": "nacos", "password": "nacos"})["accessToken"]

print("=== 1. Admin 列表 ===")
d = get("/v3/admin/ai/prompt/list", {"pageNo": 1, "pageSize": 20, "accessToken": token})
rows = d.get("data", {}).get("pageItems", [])
print("总数:", len(rows))
for r in rows:
    print("  ", r.get("promptKey"), "| latestVersion =", r.get("latestVersion"))

print()
print("=== 2. 客户端逐个读回（验证 online 可读） ===")
keys = ["rag-planner-system", "rag-evidence-system", "rag-final-answer-system",
        "rag-final-answer-with-history-system", "rag-rewrite-system", "rag-hyde-system",
        "rag-graph-understanding-system", "rag-direct-answer-system"]
for k in keys:
    try:
        p = get("/v3/client/ai/prompt", {"promptKey": k, "accessToken": token})["data"]
        t = (p.get("template") or "").replace("\n", " ")
        print("  [OK] %-42s v%s | %s" % (k, p.get("version"), t[:50]))
    except Exception as e:
        print("  [FAIL] %s -> %s" % (k, e))
