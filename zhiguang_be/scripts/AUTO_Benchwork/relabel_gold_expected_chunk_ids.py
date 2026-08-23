#!/usr/bin/env python3
"""Create a reviewable Gold-ID relabelling proposal from the live ES vector index.

The script is deliberately read-only toward Elasticsearch and never overwrites
gold-dataset-v1.json.  Exact evidence matches can be proposed automatically;
lexical matches remain review candidates because a retrieval result is not, by
itself, proof that it is the intended Gold evidence.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Callable


DEFAULT_INDEX = "zhiguang-ai-index"
SCHEMA_VERSION = "rag-benchmark-gold-relabel-proposal-v1"
SOURCE_FIELDS = [
    "content",
    "metadata.chunkId",
    "metadata.postId",
    "metadata.position",
    "metadata.title",
    "metadata.sectionTitle",
    "metadata.sectionType",
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate a reviewable expected_chunk_ids proposal from the current Elasticsearch index."
    )
    parser.add_argument(
        "--es-url",
        default=os.getenv("RAG_BENCHMARK_ES_URL", "http://127.0.0.1:9200"),
        help="Elasticsearch URL reachable from the machine running this script.",
    )
    parser.add_argument(
        "--index",
        default=os.getenv("RAG_BENCHMARK_ES_INDEX", DEFAULT_INDEX),
        help=f"Vector index name; defaults to {DEFAULT_INDEX}.",
    )
    parser.add_argument(
        "--dataset",
        type=Path,
        default=Path(__file__).with_name("gold-dataset-v1.json"),
    )
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument(
        "--report-output",
        type=Path,
        default=None,
        help="Defaults to <output>.md.",
    )
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--timeout-seconds", type=int, default=20)
    return parser.parse_args()


def read_dataset(path: Path) -> list[dict[str, Any]]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise RuntimeError(f"Cannot read Gold dataset {path}: {error}") from error
    if not isinstance(value, list) or not value:
        raise RuntimeError("Gold dataset must be a non-empty JSON array")
    for index, item in enumerate(value):
        if not isinstance(item, dict):
            raise RuntimeError(f"Gold dataset item {index} is not an object")
        if not isinstance(item.get("id"), str) or not item["id"].strip():
            raise RuntimeError(f"Gold dataset item {index} has no id")
        if not isinstance(item.get("question"), str) or not item["question"].strip():
            raise RuntimeError(f"Gold dataset item {index} has no question")
        evidence = item.get("evidence")
        if not isinstance(evidence, dict):
            raise RuntimeError(f"Gold dataset item {index} has no evidence object")
        if not isinstance(evidence.get("title"), str) or not evidence["title"].strip():
            raise RuntimeError(f"Gold dataset item {index} has no evidence.title")
        if not isinstance(evidence.get("excerpt"), str) or not evidence["excerpt"].strip():
            raise RuntimeError(f"Gold dataset item {index} has no evidence.excerpt")
    return value


def write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(content, encoding="utf-8", newline="\n")
    temporary.replace(path)


def normalized_text(value: str) -> str:
    return re.sub(r"\s+", "", value).lower()


def preview(value: str, length: int = 180) -> str:
    compact = re.sub(r"\s+", " ", value).strip()
    return compact if len(compact) <= length else compact[:length] + "…"


def safe_es_url(es_url: str) -> str:
    parsed = urllib.parse.urlsplit(es_url)
    if not parsed.scheme or not parsed.netloc:
        return es_url.rstrip("/")
    host = parsed.hostname or ""
    port = f":{parsed.port}" if parsed.port else ""
    return urllib.parse.urlunsplit((parsed.scheme, host + port, parsed.path, "", "")).rstrip("/")


def request_json(
        es_url: str,
        index: str,
        body: dict[str, Any],
        timeout_seconds: int,
        opener: Callable[..., Any] = urllib.request.urlopen,
) -> dict[str, Any]:
    endpoint = f"{es_url.rstrip('/')}/{urllib.parse.quote(index, safe='')}/_search"
    request = urllib.request.Request(
        endpoint,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        method="POST",
        headers={"Accept": "application/json", "Content-Type": "application/json"},
    )
    try:
        with opener(request, timeout=timeout_seconds) as response:
            value = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        try:
            detail = error.read().decode("utf-8", errors="replace")[:500]
        finally:
            error.close()
        raise RuntimeError(f"Elasticsearch HTTP {error.code}: {detail or error.reason}") from error
    except (urllib.error.URLError, TimeoutError, OSError, json.JSONDecodeError) as error:
        raise RuntimeError(f"Elasticsearch request failed: {type(error).__name__}: {error}") from error
    if not isinstance(value, dict):
        raise RuntimeError("Elasticsearch returned a non-object JSON response")
    return value


def hits(response: dict[str, Any]) -> list[dict[str, Any]]:
    raw_hits = response.get("hits", {})
    values = raw_hits.get("hits", []) if isinstance(raw_hits, dict) else []
    return [item for item in values if isinstance(item, dict)] if isinstance(values, list) else []


def exact_title_query(title: str) -> dict[str, Any]:
    return {
        "size": 100,
        "_source": SOURCE_FIELDS,
        "sort": [{"metadata.position": {"order": "asc"}}],
        "query": {"term": {"metadata.title.keyword": title}},
    }


def lexical_query(question: str, title: str, top_k: int) -> dict[str, Any]:
    return {
        "size": top_k,
        "_source": SOURCE_FIELDS,
        "query": {
            "bool": {
                "should": [
                    {"multi_match": {
                        "query": title,
                        "fields": ["metadata.title^6", "content^2"],
                        "type": "best_fields",
                    }},
                    {"multi_match": {
                        "query": question,
                        "fields": ["metadata.title^4", "content^3"],
                        "type": "best_fields",
                    }},
                ],
                "minimum_should_match": 1,
            }
        },
    }


def candidate_from_hit(hit: dict[str, Any], evidence_excerpt: str, title: str, strategy: str) -> dict[str, Any] | None:
    source = hit.get("_source")
    if not isinstance(source, dict):
        return None
    metadata = source.get("metadata")
    if not isinstance(metadata, dict):
        return None
    chunk_id = metadata.get("chunkId")
    content = source.get("content")
    if not isinstance(chunk_id, str) or not chunk_id.strip() or not isinstance(content, str):
        return None
    evidence_exact = normalized_text(evidence_excerpt) in normalized_text(content)
    title_exact = metadata.get("title") == title
    return {
        "chunkId": chunk_id,
        "postId": metadata.get("postId"),
        "position": metadata.get("position"),
        "title": metadata.get("title"),
        "sectionTitle": metadata.get("sectionTitle"),
        "score": hit.get("_score"),
        "strategy": strategy,
        "titleExact": title_exact,
        "evidenceExcerptExact": evidence_exact,
        "contentPreview": preview(content),
    }


def unique_candidates(candidates: list[dict[str, Any]]) -> list[dict[str, Any]]:
    seen: set[str] = set()
    unique: list[dict[str, Any]] = []
    for candidate in candidates:
        chunk_id = candidate["chunkId"]
        if chunk_id in seen:
            continue
        seen.add(chunk_id)
        unique.append(candidate)
    return unique


def relabel_case(
        case: dict[str, Any],
        es_url: str,
        index: str,
        top_k: int,
        timeout_seconds: int,
        opener: Callable[..., Any] = urllib.request.urlopen,
) -> dict[str, Any]:
    evidence = case["evidence"]
    title = evidence["title"]
    excerpt = evidence["excerpt"]
    exact_hits = hits(request_json(es_url, index, exact_title_query(title), timeout_seconds, opener))
    candidates = [
        candidate
        for hit in exact_hits
        if (candidate := candidate_from_hit(hit, excerpt, title, "EXACT_TITLE")) is not None
    ]
    if not candidates:
        lexical_hits = hits(request_json(es_url, index, lexical_query(case["question"], title, top_k), timeout_seconds, opener))
        candidates = [
            candidate
            for hit in lexical_hits
            if (candidate := candidate_from_hit(hit, excerpt, title, "LEXICAL")) is not None
        ]
    candidates = unique_candidates(candidates)
    exact_evidence_candidates = [candidate for candidate in candidates if candidate["evidenceExcerptExact"]]
    if exact_evidence_candidates:
        status = "AUTO_MATCHED"
        proposed_ids = [candidate["chunkId"] for candidate in exact_evidence_candidates]
        review_candidate_ids: list[str] = []
        strategy = "EXACT_EVIDENCE_IN_CURRENT_INDEX"
    elif candidates:
        status = "REVIEW_REQUIRED"
        proposed_ids = []
        review_candidate_ids = [candidate["chunkId"] for candidate in candidates]
        strategy = "LEXICAL_CANDIDATES_ONLY"
    else:
        status = "UNRESOLVED"
        proposed_ids = []
        review_candidate_ids = []
        strategy = "NO_CURRENT_INDEX_CANDIDATE"
    return {
        "id": case["id"],
        "question": case["question"],
        "previousExpectedChunkIds": case.get("expected_chunk_ids", []),
        "proposedExpectedChunkIds": proposed_ids,
        "reviewCandidateChunkIds": review_candidate_ids,
        "status": status,
        "strategy": strategy,
        "evidence": {"title": title, "excerpt": excerpt},
        "candidates": candidates[:top_k],
    }


def build_proposal(
        dataset: list[dict[str, Any]],
        es_url: str,
        index: str,
        top_k: int,
        timeout_seconds: int,
        opener: Callable[..., Any] = urllib.request.urlopen,
) -> dict[str, Any]:
    if top_k <= 0 or timeout_seconds <= 0:
        raise RuntimeError("top-k and timeout-seconds must be positive")
    cases = [
        relabel_case(case, es_url, index, top_k, timeout_seconds, opener)
        for case in dataset
    ]
    counts = {status: sum(case["status"] == status for case in cases) for status in (
        "AUTO_MATCHED", "REVIEW_REQUIRED", "UNRESOLVED"
    )}
    return {
        "schemaVersion": SCHEMA_VERSION,
        "generatedAt": datetime.now(UTC).isoformat(),
        "sourceDataset": "gold-dataset-v1.json",
        "esUrl": safe_es_url(es_url),
        "index": index,
        "caseCount": len(cases),
        "statusCounts": counts,
        "safety": "This is a review proposal. Do not overwrite the approved Gold dataset until REVIEW_REQUIRED cases are manually verified.",
        "cases": cases,
    }


def markdown_cell(value: Any) -> str:
    return str(value if value is not None else "-").replace("|", "\\|").replace("\n", " ")


def proposal_markdown(proposal: dict[str, Any]) -> str:
    counts = proposal["statusCounts"]
    lines = [
        "# Gold expected_chunk_ids relabelling proposal",
        "",
        f"- Source dataset: `{proposal['sourceDataset']}`",
        f"- Elasticsearch index: `{proposal['index']}`",
        f"- Cases: {proposal['caseCount']}",
        f"- Auto-matched: {counts['AUTO_MATCHED']}",
        f"- Review required: {counts['REVIEW_REQUIRED']}",
        f"- Unresolved: {counts['UNRESOLVED']}",
        "",
        "> This proposal never changes the approved Gold dataset. Only `AUTO_MATCHED` rows have an exact evidence excerpt in a current indexed chunk; review every other row before promotion.",
        "",
        "| Case | Status | Previous IDs | Proposed IDs | Review candidate IDs |",
        "| --- | --- | --- | --- | --- |",
    ]
    for case in proposal["cases"]:
        lines.append(
            f"| {markdown_cell(case['id'])} | {markdown_cell(case['status'])} | "
            f"{markdown_cell(', '.join(case['previousExpectedChunkIds']))} | "
            f"{markdown_cell(', '.join(case['proposedExpectedChunkIds']))} | "
            f"{markdown_cell(', '.join(case['reviewCandidateChunkIds']))} |"
        )
    lines.extend(["", "## Review rule", "", "Promote a proposed chunk ID only after its content is confirmed to support that Gold question. A high BM25/lexical score alone is not sufficient.", ""])
    return "\n".join(lines)


def main() -> int:
    args = parse_args()
    dataset = read_dataset(args.dataset)
    proposal = build_proposal(
        dataset, args.es_url, args.index, args.top_k, args.timeout_seconds
    )
    report_output = args.report_output or args.output.with_suffix(".md")
    write_text(args.output, json.dumps(proposal, ensure_ascii=False, indent=2) + "\n")
    write_text(report_output, proposal_markdown(proposal))
    print(json.dumps({
        "caseCount": proposal["caseCount"],
        "statusCounts": proposal["statusCounts"],
        "output": str(args.output),
        "reportOutput": str(report_output),
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"Gold relabelling proposal failed: {type(error).__name__}: {error}", file=sys.stderr)
        raise SystemExit(1)
