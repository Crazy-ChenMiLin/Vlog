#!/usr/bin/env python3
"""Build a compact human-review pool for an automotive T2Retrieval Gold set."""

from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path

from build_t2_topic_gold import clean_text, evidence_overlap, load_evidence, read_parquet


KEYWORDS = (
    "汽车", "车辆", "发动机", "变速箱", "离合器", "轮胎", "机油", "刹车",
    "制动", "电瓶", "蓄电池", "充电机", "车灯", "底盘", "方向盘", "冷却液",
    "防冻液", "火花塞", "发电机", "涡轮", "油耗", "胎压", "中控",
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--queries", type=Path, required=True)
    parser.add_argument("--qrels", type=Path, required=True)
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    relevant: dict[str, list[str]] = defaultdict(list)
    for row in read_parquet(args.qrels).to_pylist():
        if int(row.get("score", 0)) > 0:
            relevant[str(row["query-id"])].append(str(row["corpus-id"]))

    candidates = []
    for row in read_parquet(args.queries).to_pylist():
        query_id = str(row.get("_id"))
        question = clean_text(row.get("text"))
        if not relevant.get(query_id) or not any(keyword in question for keyword in KEYWORDS):
            continue
        candidates.append(
            {
                "query_id": query_id,
                "question": question,
                "expected_chunk_ids": sorted(set(relevant[query_id])),
            }
        )

    wanted_ids = {chunk_id for item in candidates for chunk_id in item["expected_chunk_ids"]}
    evidence = load_evidence(args.corpus, wanted_ids)
    for item in candidates:
        ranked = sorted(
            (
                {
                    "chunk_id": chunk_id,
                    "overlap": round(evidence_overlap(item["question"], evidence.get(chunk_id, {}).get("excerpt", "")), 4),
                    "excerpt": evidence.get(chunk_id, {}).get("excerpt", ""),
                }
                for chunk_id in item["expected_chunk_ids"]
            ),
            key=lambda candidate: (-candidate["overlap"], candidate["chunk_id"]),
        )
        item["evidence_candidates"] = ranked

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(candidates, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"candidates": len(candidates), "output": str(args.output)}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
