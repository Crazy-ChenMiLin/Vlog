#!/usr/bin/env python3
"""Import the pre-chunked T2Retrieval corpus into the existing RAG index."""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path
from typing import Any

from t2_common import add_es_arguments, bulk_failures, bulk_request, es_client, require_index


DEFAULT_CORPUS = Path("target/rag-benchmark/public-datasets/t2retrieval/raw/corpus.parquet")
DATASET_NAME = "mteb/T2Retrieval"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", type=Path, default=DEFAULT_CORPUS)
    parser.add_argument("--batch-size", type=int, default=500)
    parser.add_argument("--max-docs", type=int, default=0, help="0 imports all rows.")
    parser.add_argument("--report", type=Path)
    add_es_arguments(parser)
    args = parser.parse_args()
    if args.batch_size < 1:
        parser.error("--batch-size must be positive")
    if args.max_docs < 0:
        parser.error("--max-docs cannot be negative")
    return args


def pyarrow_parquet() -> Any:
    try:
        import pyarrow.parquet as parquet
    except ImportError as error:
        raise RuntimeError("pyarrow is required: python -m pip install pyarrow") from error
    return parquet


def source_document(corpus_id: str, text: str, title: str) -> dict[str, Any]:
    return {
        "id": corpus_id,
        "content": text,
        "metadata": {
            "chunkId": corpus_id,
            "externalDocId": corpus_id,
            "dataset": DATASET_NAME,
            "benchmarkOnly": True,
            "title": title,
            "sectionTitle": title,
            "sectionType": "PUBLIC_DATASET",
            "position": 0,
        },
    }


def import_batch(client: Any, index: str, rows: list[dict[str, Any]]) -> tuple[int, list[dict[str, Any]]]:
    operations: list[dict[str, Any]] = []
    for row in rows:
        corpus_id = str(row["_id"])
        operations.append({"index": {"_index": index, "_id": corpus_id}})
        operations.append(source_document(corpus_id, str(row.get("text") or ""), str(row.get("title") or "")))
    response = bulk_request(client, operations)
    failures = bulk_failures(response)
    return len(rows) - len(failures), failures


def main() -> int:
    args = parse_args()
    if not args.corpus.is_file():
        raise RuntimeError(f"Corpus file not found: {args.corpus}")
    parquet = pyarrow_parquet()
    client = es_client(args)
    require_index(client, args.index, 4096)

    parquet_file = parquet.ParquetFile(args.corpus)
    expected = parquet_file.metadata.num_rows
    limit = min(expected, args.max_docs) if args.max_docs else expected
    started = time.time()
    attempted = succeeded = failed = 0
    failure_samples: list[dict[str, Any]] = []
    pending: list[dict[str, Any]] = []

    for arrow_batch in parquet_file.iter_batches(batch_size=args.batch_size, columns=["_id", "text", "title"]):
        for row in arrow_batch.to_pylist():
            if attempted + len(pending) >= limit:
                break
            pending.append(row)
        if pending:
            ok, failures = import_batch(client, args.index, pending)
            attempted += len(pending)
            succeeded += ok
            failed += len(failures)
            failure_samples.extend(failures[: max(0, 20 - len(failure_samples))])
            print(json.dumps({"attempted": attempted, "succeeded": succeeded, "failed": failed}))
            pending = []
        if attempted >= limit:
            break

    client.request("POST", f"/{args.index}/_refresh")
    report = {
        "schemaVersion": "t2-corpus-import-report-v1",
        "dataset": DATASET_NAME,
        "index": args.index,
        "sourceRows": expected,
        "requestedRows": limit,
        "attempted": attempted,
        "succeeded": succeeded,
        "failed": failed,
        "elapsedSeconds": round(time.time() - started, 3),
        "failureSamples": failure_samples,
    }
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))
    return 1 if failed else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("Interrupted", file=sys.stderr)
        sys.exit(130)
    except Exception as error:
        print(f"ERROR: {type(error).__name__}: {error}", file=sys.stderr)
        sys.exit(1)
