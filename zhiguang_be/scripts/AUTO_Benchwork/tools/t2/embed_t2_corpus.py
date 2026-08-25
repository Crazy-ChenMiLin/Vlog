#!/usr/bin/env python3
"""Generate NVIDIA passage embeddings for imported T2Retrieval documents."""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path
from typing import Any

from t2_common import (
    NvidiaEmbeddingClient,
    add_es_arguments,
    add_nvidia_arguments,
    bulk_failures,
    bulk_request,
    es_client,
    require_index,
)


DATASET_NAME = "mteb/T2Retrieval"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--page-size", type=int, default=64)
    parser.add_argument("--embedding-batch-size", type=int, default=8)
    parser.add_argument(
        "--embedding-batch-max-chars",
        type=int,
        default=9000,
        help="Maximum combined input characters per NVIDIA request.",
    )
    parser.add_argument(
        "--embedding-input-max-chars",
        type=int,
        default=2000,
        help="Maximum characters sent for one document; Elasticsearch content remains unchanged.",
    )
    parser.add_argument("--max-docs", type=int, default=0, help="0 processes every matching document.")
    parser.add_argument("--slice-id", type=int, default=0, help="Zero-based Elasticsearch scroll slice ID.")
    parser.add_argument("--slice-max", type=int, default=1, help="Number of disjoint Elasticsearch scroll slices.")
    parser.add_argument("--overwrite", action="store_true", help="Re-embed documents that already have vectors.")
    parser.add_argument("--report", type=Path)
    add_es_arguments(parser)
    add_nvidia_arguments(parser)
    args = parser.parse_args()
    if (
        args.page_size < 1
        or args.embedding_batch_size < 1
        or args.embedding_batch_max_chars < 1
        or args.embedding_input_max_chars < 1
    ):
        parser.error("batch sizes must be positive")
    if args.max_docs < 0:
        parser.error("--max-docs cannot be negative")
    if args.slice_max < 1 or args.slice_id < 0 or args.slice_id >= args.slice_max:
        parser.error("require 0 <= --slice-id < --slice-max")
    return args


def search_body(page_size: int, overwrite: bool, slice_id: int = 0, slice_max: int = 1) -> dict[str, Any]:
    filters: list[dict[str, Any]] = [
        {"term": {"metadata.dataset.keyword": DATASET_NAME}},
    ]
    boolean: dict[str, Any] = {"filter": filters}
    if not overwrite:
        boolean["must_not"] = [{"exists": {"field": "embedding"}}]
    body: dict[str, Any] = {
        "size": page_size,
        "sort": ["_doc"],
        "_source": ["content"],
        "query": {"bool": boolean},
    }
    if slice_max > 1:
        body["slice"] = {"id": slice_id, "max": slice_max}
    return body


def embed_resilient(
    embedding_client: NvidiaEmbeddingClient,
    documents: list[dict[str, str]],
    failures: list[dict[str, str]],
) -> list[tuple[dict[str, str], list[float]]]:
    if not documents:
        return []
    try:
        vectors = embedding_client.embed([item["content"] for item in documents], "passage")
        return list(zip(documents, vectors, strict=True))
    except Exception as error:
        if len(documents) == 1:
            document = documents[0]
            for fallback_chars in (1000, 500, 200, 50):
                if len(document["content"]) <= fallback_chars:
                    continue
                try:
                    vector = embedding_client.embed(
                        [document["content"][:fallback_chars]],
                        "passage",
                    )[0]
                    return [(document, vector)]
                except Exception:
                    continue
            failures.append({"id": document["id"], "error": f"{type(error).__name__}: {error}"})
            return []
        middle = len(documents) // 2
        return embed_resilient(embedding_client, documents[:middle], failures) + embed_resilient(
            embedding_client, documents[middle:], failures
        )


def embedding_batches(
    documents: list[dict[str, str]],
    max_items: int,
    max_chars: int,
) -> list[list[dict[str, str]]]:
    batches: list[list[dict[str, str]]] = []
    current: list[dict[str, str]] = []
    current_chars = 0
    for document in documents:
        document_chars = len(document["content"])
        if current and (len(current) >= max_items or current_chars + document_chars > max_chars):
            batches.append(current)
            current = []
            current_chars = 0
        current.append(document)
        current_chars += document_chars
    if current:
        batches.append(current)
    return batches


def update_vectors(client: Any, index: str, embedded: list[tuple[dict[str, str], list[float]]]) -> list[dict[str, Any]]:
    operations: list[dict[str, Any]] = []
    for document, vector in embedded:
        operations.append({"update": {"_index": index, "_id": document["id"]}})
        operations.append({"doc": {"embedding": vector}})
    if not operations:
        return []
    return bulk_failures(bulk_request(client, operations))


def main() -> int:
    args = parse_args()
    client = es_client(args)
    require_index(client, args.index, args.embedding_dimensions)
    embedding_client = NvidiaEmbeddingClient(
        api_key=args.nvidia_api_key,
        base_url=args.embedding_url,
        model=args.embedding_model,
        dimensions=args.embedding_dimensions,
        timeout_seconds=args.timeout_seconds,
        max_retries=args.max_retries,
    )

    response = client.request(
        "POST",
        f"/{args.index}/_search?scroll=10m",
        search_body(args.page_size, args.overwrite, args.slice_id, args.slice_max),
    )
    scroll_id = response.get("_scroll_id")
    started = time.time()
    attempted = embedded_count = failed = 0
    failure_samples: list[dict[str, Any]] = []

    try:
        while True:
            hits = response.get("hits", {}).get("hits", [])
            if not hits:
                break
            documents = [
                {
                    "id": str(hit["_id"]),
                    "content": str((hit.get("_source") or {}).get("content") or "")[
                        : args.embedding_input_max_chars
                    ],
                }
                for hit in hits
            ]
            if args.max_docs:
                documents = documents[: max(0, args.max_docs - attempted)]
            if not documents:
                break

            for batch in embedding_batches(
                documents,
                args.embedding_batch_size,
                args.embedding_batch_max_chars,
            ):
                embedding_failures: list[dict[str, str]] = []
                embedded = embed_resilient(embedding_client, batch, embedding_failures)
                update_failures = update_vectors(client, args.index, embedded)
                attempted += len(batch)
                embedded_count += len(embedded) - len(update_failures)
                failed += len(embedding_failures) + len(update_failures)
                failure_samples.extend(embedding_failures[: max(0, 30 - len(failure_samples))])
                for item in update_failures[: max(0, 30 - len(failure_samples))]:
                    failure_samples.append({"id": item.get("_id"), "error": item.get("error")})
                print(
                    json.dumps(
                        {"attempted": attempted, "embedded": embedded_count, "failed": failed},
                        ensure_ascii=False,
                    )
                )
                if embedded_count == 0 and failed == attempted:
                    first_error = failure_samples[0].get("error", "unknown error") if failure_samples else "unknown error"
                    raise RuntimeError(f"The first embedding batch failed completely; stopped early: {first_error}")
                if args.max_docs and attempted >= args.max_docs:
                    break
            if args.max_docs and attempted >= args.max_docs:
                break
            if not scroll_id:
                break
            response = client.request("POST", "/_search/scroll", {"scroll": "10m", "scroll_id": scroll_id})
            scroll_id = response.get("_scroll_id", scroll_id)
    finally:
        if scroll_id:
            try:
                client.request("DELETE", "/_search/scroll", {"scroll_id": [scroll_id]})
            except Exception:
                pass

    client.request("POST", f"/{args.index}/_refresh")
    report = {
        "schemaVersion": "t2-embedding-report-v1",
        "dataset": DATASET_NAME,
        "index": args.index,
        "model": args.embedding_model,
        "dimensions": args.embedding_dimensions,
        "inputMaxChars": args.embedding_input_max_chars,
        "batchMaxChars": args.embedding_batch_max_chars,
        "overwrite": args.overwrite,
        "sliceId": args.slice_id,
        "sliceMax": args.slice_max,
        "attempted": attempted,
        "embedded": embedded_count,
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
