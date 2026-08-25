#!/usr/bin/env python3
"""Evaluate BM25, vector, and local-RRF retrieval against T2Retrieval qrels."""

from __future__ import annotations

import argparse
import json
import random
import statistics
import sys
import time
from collections import defaultdict
from pathlib import Path
from typing import Any

from t2_common import NvidiaEmbeddingClient, add_es_arguments, add_nvidia_arguments, es_client, require_index


DATASET_NAME = "mteb/T2Retrieval"
DEFAULT_ROOT = Path("target/rag-benchmark/public-datasets/t2retrieval/raw")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--queries", type=Path, default=DEFAULT_ROOT / "queries.parquet")
    parser.add_argument("--qrels", type=Path, default=DEFAULT_ROOT / "qrels.parquet")
    parser.add_argument("--limit", type=int, default=40, help="0 evaluates every query.")
    parser.add_argument("--seed", type=int, default=20260824)
    parser.add_argument("--ks", type=int, nargs="+", default=[1, 5, 10, 20])
    parser.add_argument("--modes", nargs="+", choices=["bm25", "vector", "hybrid"], default=["bm25", "vector", "hybrid"])
    parser.add_argument("--num-candidates", type=int, default=200)
    parser.add_argument("--rrf-k", type=int, default=60)
    parser.add_argument("--report", type=Path, default=Path("target/rag-benchmark/t2-retrieval-report.json"))
    parser.add_argument("--markdown-report", type=Path, default=Path("target/rag-benchmark/t2-retrieval-report.md"))
    add_es_arguments(parser)
    add_nvidia_arguments(parser)
    args = parser.parse_args()
    if args.limit < 0 or any(k < 1 for k in args.ks):
        parser.error("--limit must be non-negative and every K must be positive")
    return args


def read_parquet(path: Path) -> list[dict[str, Any]]:
    try:
        import pyarrow.parquet as parquet
    except ImportError as error:
        raise RuntimeError("pyarrow is required: python -m pip install pyarrow") from error
    if not path.is_file():
        raise RuntimeError(f"Parquet file not found: {path}")
    return parquet.read_table(path).to_pylist()


def select_queries(rows: list[dict[str, Any]], relevant: dict[str, set[str]], limit: int, seed: int) -> list[dict[str, str]]:
    eligible = [
        {"id": str(row["_id"]), "text": str(row.get("text") or "")}
        for row in rows
        if str(row["_id"]) in relevant and str(row.get("text") or "").strip()
    ]
    eligible.sort(key=lambda item: item["id"])
    if not limit or limit >= len(eligible):
        return eligible
    randomizer = random.Random(seed)
    selected = randomizer.sample(eligible, limit)
    return sorted(selected, key=lambda item: item["id"])


def hit_ids(response: dict[str, Any]) -> list[str]:
    return [str(hit.get("_id")) for hit in response.get("hits", {}).get("hits", []) if hit.get("_id") is not None]


def bm25_search(client: Any, index: str, query: str, size: int) -> list[str]:
    response = client.request(
        "POST",
        f"/{index}/_search",
        {
            "size": size,
            "_source": False,
            "query": {
                "bool": {
                    "must": [{"match": {"content": {"query": query}}}],
                    "filter": [{"term": {"metadata.dataset.keyword": DATASET_NAME}}],
                }
            },
        },
    )
    return hit_ids(response)


def vector_search(client: Any, index: str, vector: list[float], size: int, num_candidates: int) -> list[str]:
    response = client.request(
        "POST",
        f"/{index}/_search",
        {
            "size": size,
            "_source": False,
            "knn": {
                "field": "embedding",
                "query_vector": vector,
                "k": size,
                "num_candidates": max(num_candidates, size),
                "filter": {"term": {"metadata.dataset.keyword": DATASET_NAME}},
            },
        },
    )
    return hit_ids(response)


def rrf_fuse(rankings: list[list[str]], size: int, rrf_k: int) -> list[str]:
    scores: dict[str, float] = defaultdict(float)
    best_rank: dict[str, int] = {}
    for ranking in rankings:
        for rank, document_id in enumerate(ranking, start=1):
            scores[document_id] += 1.0 / (rrf_k + rank)
            best_rank[document_id] = min(best_rank.get(document_id, rank), rank)
    ordered = sorted(scores, key=lambda item: (-scores[item], best_rank[item], item))
    return ordered[:size]


def metrics_for(ranking: list[str], relevant: set[str], ks: list[int]) -> dict[str, float]:
    result: dict[str, float] = {}
    first_rank = next((rank for rank, document_id in enumerate(ranking, start=1) if document_id in relevant), None)
    result[f"mrr@{max(ks)}"] = 0.0 if first_rank is None else 1.0 / first_rank
    for k in ks:
        found = len(set(ranking[:k]) & relevant)
        result[f"recall@{k}"] = found / len(relevant)
        result[f"hit@{k}"] = 1.0 if found else 0.0
    return result


def aggregate(rows: list[dict[str, float]]) -> dict[str, float]:
    if not rows:
        return {}
    return {
        key: round(statistics.fmean(row[key] for row in rows), 6)
        for key in rows[0]
    }


def markdown(report: dict[str, Any]) -> str:
    mrr_key = f"mrr@{max(report['ks'])}"
    lines = [
        "# T2Retrieval 检索评测",
        "",
        f"- 状态：`{report['status']}`",
        f"- 索引：`{report['index']}`",
        f"- 成功题数：{report['successfulQueries']}",
        f"- 失败题数：{report['failedQueries']}",
        f"- T2 文档数：{report['corpusCoverage']['totalDocuments']}",
        f"- 已向量化：{report['corpusCoverage']['embeddedDocuments']}",
        f"- 随机种子：{report['seed']}",
        "",
        f"| 模式 | MRR@{max(report['ks'])} | " + " | ".join(f"Recall@{k}" for k in report["ks"]) + " |",
        "| --- | ---: | " + " | ".join("---:" for _ in report["ks"]) + " |",
    ]
    for mode, values in report["metrics"].items():
        cells = [mode, f"{values.get(mrr_key, 0):.4f}"] + [
            f"{values.get(f'recall@{k}', 0):.4f}" for k in report["ks"]
        ]
        lines.append("| " + " | ".join(cells) + " |")
    lines.extend(["", "## 失败样本", ""])
    if report["failureSamples"]:
        for item in report["failureSamples"]:
            lines.append(f"- `{item['queryId']}`：{item['error']}")
    else:
        lines.append("无。")
    return "\n".join(lines) + "\n"


def main() -> int:
    args = parse_args()
    queries = read_parquet(args.queries)
    qrel_rows = read_parquet(args.qrels)
    relevant: dict[str, set[str]] = defaultdict(set)
    for row in qrel_rows:
        if int(row.get("score", 0)) > 0:
            relevant[str(row["query-id"])].add(str(row["corpus-id"]))
    selected = select_queries(queries, relevant, args.limit, args.seed)
    if not selected:
        raise RuntimeError("No eligible queries were selected")

    client = es_client(args)
    require_index(client, args.index, args.embedding_dimensions)
    needs_vectors = bool({"vector", "hybrid"} & set(args.modes))
    dataset_filter = {"term": {"metadata.dataset.keyword": DATASET_NAME}}
    corpus_count = int(client.request("POST", f"/{args.index}/_count", {"query": dataset_filter}).get("count", 0))
    embedded_count = int(
        client.request(
            "POST",
            f"/{args.index}/_count",
            {"query": {"bool": {"filter": [dataset_filter, {"exists": {"field": "embedding"}}]}}},
        ).get("count", 0)
    )
    if corpus_count == 0:
        raise RuntimeError("No mteb/T2Retrieval documents exist in the target index; run import_t2_corpus.py first")
    if needs_vectors and embedded_count == 0:
        raise RuntimeError("No T2Retrieval document has an embedding; run embed_t2_corpus.py first")
    embedding_client = None
    if needs_vectors:
        embedding_client = NvidiaEmbeddingClient(
            api_key=args.nvidia_api_key,
            base_url=args.embedding_url,
            model=args.embedding_model,
            dimensions=args.embedding_dimensions,
            timeout_seconds=args.timeout_seconds,
            max_retries=args.max_retries,
        )

    max_k = max(args.ks)
    per_mode: dict[str, list[dict[str, float]]] = {mode: [] for mode in args.modes}
    per_query: list[dict[str, Any]] = []
    failures: list[dict[str, str]] = []
    started = time.time()

    for number, query in enumerate(selected, start=1):
        try:
            bm25 = bm25_search(client, args.index, query["text"], max_k) if {"bm25", "hybrid"} & set(args.modes) else []
            vector: list[str] = []
            if needs_vectors and embedding_client is not None:
                query_vector = embedding_client.embed([query["text"]], "query")[0]
                vector = vector_search(client, args.index, query_vector, max_k, args.num_candidates)
            rankings = {
                "bm25": bm25,
                "vector": vector,
                "hybrid": rrf_fuse([bm25, vector], max_k, args.rrf_k),
            }
            query_metrics: dict[str, Any] = {}
            for mode in args.modes:
                values = metrics_for(rankings[mode], relevant[query["id"]], args.ks)
                per_mode[mode].append(values)
                query_metrics[mode] = {"metrics": values, "ranking": rankings[mode]}
            per_query.append(
                {
                    "queryId": query["id"],
                    "question": query["text"],
                    "relevantCorpusIds": sorted(relevant[query["id"]]),
                    "results": query_metrics,
                }
            )
            print(json.dumps({"completed": number, "total": len(selected), "queryId": query["id"]}, ensure_ascii=False))
        except Exception as error:
            failures.append({"queryId": query["id"], "error": f"{type(error).__name__}: {error}"})
            print(json.dumps({"completed": number, "total": len(selected), "queryId": query["id"], "failed": True}))

    successful = len(per_query)
    status = "COMPLETE" if not failures else ("PARTIAL" if successful else "FAILED")
    report = {
        "schemaVersion": "t2-retrieval-evaluation-v1",
        "status": status,
        "dataset": DATASET_NAME,
        "index": args.index,
        "embeddingModel": args.embedding_model if needs_vectors else None,
        "requestedQueries": len(selected),
        "successfulQueries": successful,
        "failedQueries": len(failures),
        "seed": args.seed,
        "ks": sorted(set(args.ks)),
        "corpusCoverage": {
            "totalDocuments": corpus_count,
            "embeddedDocuments": embedded_count,
            "embeddingCoverage": round(embedded_count / corpus_count, 6),
        },
        "metrics": {mode: aggregate(rows) for mode, rows in per_mode.items()},
        "elapsedSeconds": round(time.time() - started, 3),
        "selectedQueryIds": [query["id"] for query in selected],
        "perQuery": per_query,
        "failureSamples": failures[:30],
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    args.markdown_report.parent.mkdir(parents=True, exist_ok=True)
    args.markdown_report.write_text(markdown(report), encoding="utf-8")
    print(json.dumps({key: report[key] for key in ("status", "successfulQueries", "failedQueries", "metrics")}, ensure_ascii=False))
    return 1 if status == "FAILED" else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("Interrupted", file=sys.stderr)
        sys.exit(130)
    except Exception as error:
        print(f"ERROR: {type(error).__name__}: {error}", file=sys.stderr)
        sys.exit(1)
