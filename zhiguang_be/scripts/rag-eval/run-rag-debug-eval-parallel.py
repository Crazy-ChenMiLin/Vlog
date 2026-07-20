"""并行版 run-rag-debug-eval。

背景：当前 graph-on 评测极慢，因为 HyDE(juanji)/rerank(nvidia) 两个外部服务
偶发 TLS 握手失败并反复重试，单题可能卡 60-90s。顺序跑 100 题要数小时。
本脚本把问题切成 N 个 shard，用 N 个线程并发打同一个后端，重叠外部调用等待，
大幅压缩墙钟时间。行结构与 run-rag-debug-eval.py 完全一致（直接复用其函数），
产物 debug.json 可被 analyze-relation-hit.py / compare-graph-ab.py 直接使用。

用法：
  python run-rag-debug-eval-parallel.py --base-url http://localhost:18181 \
      --questions scripts/rag-eval/questions-relation-cache-100.json \
      --output target/rag-eval/graph-relation-300/graph-on.json \
      --top-k 10 --workers 6 --label graph-relation-300-on
"""
import argparse
import json
import sys
import threading
import time
import urllib.error
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

# 复用现有脚本的函数（build_row / compact_* / jwt_headers / request_debug）
# 注意：基础脚本文件名为 run-rag-debug-eval.py（含连字符，不能直接 import），
# 故用 importlib 按路径加载为模块名 run_rag_debug_eval。
sys.path.insert(0, str(Path(__file__).resolve().parent))
import importlib.util  # noqa: E402

_BASE_FILE = Path(__file__).resolve().parent / "run-rag-debug-eval.py"
_spec = importlib.util.spec_from_file_location("run_rag_debug_eval", _BASE_FILE)
base = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(base)

DONE = {}  # question -> row
ERR = {}
LOCK = threading.Lock()


def try_one(base_url, question, top_k, headers, max_retry=3):
    last = None
    for attempt in range(max_retry):
        try:
            debug = base.request_debug(base_url, question, top_k, headers)
            return base.build_row(question, debug, 0)
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, Exception) as exc:  # noqa
            last = str(exc)
            time.sleep(2 + attempt * 2)
    return {"question": question, "error": last}


def worker(questions, base_url, top_k, user_id, pk, label):
    headers = base.jwt_headers(pk, user_id)
    local = []
    for q in questions:
        row = try_one(base_url, q, top_k, headers)
        local.append(row)
        with LOCK:
            if "error" in row:
                ERR[q] = row["error"]
            else:
                DONE[q] = row
        print(f"  [{label}] {q[:24]}... {'OK' if 'error' not in row else 'ERR'}", flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://localhost:18181")
    ap.add_argument("--questions", type=Path, required=True)
    ap.add_argument("--output", type=Path, required=True)
    ap.add_argument("--top-k", type=int, default=10)
    ap.add_argument("--workers", type=int, default=6)
    ap.add_argument("--user-id", type=int, default=1)
    ap.add_argument("--private-key", type=Path, default=base.DEFAULT_PRIVATE_KEY)
    ap.add_argument("--label", default="rag-debug-parallel")
    args = ap.parse_args()

    questions = json.loads(args.questions.read_text(encoding="utf-8"))
    shards = [questions[i::args.workers] for i in range(args.workers)]

    t0 = time.perf_counter()
    threads = []
    for i, shard in enumerate(shards):
        t = threading.Thread(target=worker, args=(shard, args.base_url, args.top_k, args.user_id, args.private_key, f"w{i}"))
        t.start()
        threads.append(t)
    for t in threads:
        t.join()

    # 保持原始问题顺序
    rows = [DONE.get(q) or {"question": q, "error": ERR.get(q, "missing")} for q in questions]
    errors = [r for r in rows if "error" in r and "error" not in r.get("latencyMs", {})]  # placeholder
    errors = [r for r in rows if "error" in r and r.get("error") is not None]

    summary = {
        "label": args.label,
        "testedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "baseUrl": args.base_url,
        "endpoint": f"GET /api/v1/knowposts/qa/debug?topK={args.top_k}",
        "questionsFile": str(args.questions),
        "total": len(questions),
        "errors": len(errors),
        "keywordReturnedCount": sum(1 for row in rows if row.get("keywordCount", 0) > 0),
        "keywordOnlyCandidateTotal": sum(row.get("keywordOnlyCandidateCount", 0) for row in rows),
        "keywordCandidatesInFusedTotal": sum(row.get("keywordCandidatesInFusedCount", 0) for row in rows),
        "keywordOnlyInFusedTotal": sum(row.get("keywordOnlyInFusedCount", 0) for row in rows),
        "keywordOnlyInRerankedTop5Total": sum(row.get("keywordOnlyInRerankedTop5Count", 0) for row in rows),
        "keywordOnlyInRerankedTop5QuestionCount": sum(1 for row in rows if row.get("keywordOnlyInRerankedTop5Count", 0) > 0),
        "fusedTop1FromKeywordCount": sum(1 for row in rows if row.get("fusedTop1FromKeyword")),
        "rerankTop1FromKeywordCount": sum(1 for row in rows if row.get("rerankTop1FromKeyword")),
        "graphContextHitCount": sum(1 for row in rows if row.get("graphContextHit")),
        "graphContextHitQuestionCount": sum(1 for row in rows if row.get("graphContextHit")),
        "rows": rows,
        "errorsDetail": errors,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: summary[k] for k in ["label", "testedAt", "total", "errors", "graphContextHitCount", "graphContextHitQuestionCount"]}, ensure_ascii=False, indent=2))
    print(f"elapsed={round(time.perf_counter()-t0)}s -> {args.output}")


if __name__ == "__main__":
    main()
