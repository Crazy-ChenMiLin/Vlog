#!/usr/bin/env python3
"""Run every fixed Gold case through the deployed single-case Benchmark API.

This runner deliberately knows only case IDs.  The backend remains the source
of truth for each question, expected chunks, RAG execution, and Transcript
assembly. Results are written one file per case so a partial run is still
inspectable and can be resumed safely. At the end, the same raw Transcripts are
also exported as transcripts.jsonl plus machine-readable runtime metadata.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Callable


CASE_ID_PATTERN = re.compile(r"^gold-\d{3}$")
# This runner and its fixed Gold dataset form one reviewed Benchmark package.
BENCHMARK_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DATASET = BENCHMARK_ROOT / "datasets" / "five_scenario" / "automotive-maintenance" / "gold-v1.json"
DEFAULT_ENDPOINT = "/api/internal/rag-benchmark/single-case"


@dataclass(frozen=True)
class BenchmarkCase:
    case_id: str


class BenchmarkRequestError(RuntimeError):
    def __init__(self, message: str, retryable: bool) -> None:
        super().__init__(message)
        self.retryable = retryable


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Call the deployed Benchmark API once for every fixed Gold case."
    )
    parser.add_argument("--base-url", required=True, help="Example: https://example.com")
    parser.add_argument("--run-id", required=True, help="Stable identifier shared by all cases in this run")
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument(
        "--dataset-version",
        default="t2-automotive-maintenance-v1",
        help="Whitelisted backend scenario version.",
    )
    parser.add_argument("--output-dir", type=Path, default=None)
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--timeout-seconds", type=int, default=120)
    parser.add_argument("--retries", type=int, default=2)
    parser.add_argument("--retry-delay-seconds", type=float, default=2.0)
    parser.add_argument("--resume", action="store_true", help="Skip cases with an existing Transcript file")
    return parser.parse_args()


def require_benchmark_token() -> str:
    token = os.getenv("BENCHMARK_TOKEN", "").strip()
    if not token:
        raise RuntimeError("BENCHMARK_TOKEN is required; do not pass it on the command line")
    return token


def load_cases(dataset_path: Path) -> list[BenchmarkCase]:
    try:
        raw = json.loads(dataset_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise RuntimeError(f"Cannot read Gold dataset {dataset_path}: {error}") from error

    if not isinstance(raw, list) or not raw:
        raise RuntimeError("Gold dataset must be a non-empty JSON array")

    seen: set[str] = set()
    cases: list[BenchmarkCase] = []
    for index, entry in enumerate(raw):
        if not isinstance(entry, dict) or not isinstance(entry.get("id"), str):
            raise RuntimeError(f"Gold dataset item {index} has no string id")
        case_id = entry["id"]
        if not CASE_ID_PATTERN.fullmatch(case_id):
            raise RuntimeError(f"Gold dataset item {index} has invalid caseId: {case_id}")
        if case_id in seen:
            raise RuntimeError(f"Gold dataset contains duplicate caseId: {case_id}")
        seen.add(case_id)
        cases.append(BenchmarkCase(case_id))
    return cases


def request_transcript(
        endpoint: str,
        token: str,
        run_id: str,
        case_id: str,
        top_k: int,
        dataset_version: str,
        timeout_seconds: int,
        opener: Callable[..., Any] = urllib.request.urlopen,
) -> dict[str, Any]:
    body = json.dumps({
        "runId": run_id,
        "caseId": case_id,
        "datasetVersion": dataset_version,
        "topK": top_k,
    }).encode("utf-8")
    request = urllib.request.Request(
        endpoint,
        data=body,
        method="POST",
        headers={
            "Accept": "application/json",
            "Content-Type": "application/json",
            "X-Benchmark-Token": token,
        },
    )
    try:
        with opener(request, timeout=timeout_seconds) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        try:
            message = error.read().decode("utf-8", errors="replace")[:500]
        finally:
            error.close()
        raise BenchmarkRequestError(
            f"HTTP {error.code}: {message or error.reason}",
            retryable=error.code == 429 or error.code >= 500,
        ) from error
    except (urllib.error.URLError, TimeoutError, OSError, json.JSONDecodeError) as error:
        raise BenchmarkRequestError(f"{type(error).__name__}: {error}", retryable=True) from error

    if not isinstance(payload, dict):
        raise BenchmarkRequestError("Benchmark API returned a non-object JSON body", retryable=False)
    return payload


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(content, encoding="utf-8", newline="\n")
    temporary.replace(path)


def write_transcripts_jsonl(output_dir: Path, results: list[dict[str, Any]]) -> int:
    lines: list[str] = []
    for result in results:
        source = result.get("file")
        if not isinstance(source, str):
            continue
        try:
            transcript = json.loads(Path(source).read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise RuntimeError(f"Cannot export Transcript {source}: {error}") from error
        lines.append(json.dumps(transcript, ensure_ascii=False, separators=(",", ":")))
    write_text(output_dir / "1-1-transcripts.jsonl", "\n".join(lines) + ("\n" if lines else ""))
    return len(lines)


def collection_exit_code(manifest: dict[str, Any]) -> int:
    return 1 if manifest.get("collectionStatus") == "FAILED" else 0


def run_benchmark(
        base_url: str,
        run_id: str,
        dataset_path: Path,
        output_dir: Path,
        top_k: int,
        dataset_version: str,
        timeout_seconds: int,
        retries: int,
        retry_delay_seconds: float,
        resume: bool,
        token: str,
        opener: Callable[..., Any] = urllib.request.urlopen,
        sleeper: Callable[[float], None] = time.sleep,
) -> dict[str, Any]:
    if not CASE_ID_PATTERN.fullmatch(run_id):
        # The backend allows a broader runId; keeping it filename-safe here prevents path mistakes.
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,79}", run_id):
            raise RuntimeError("runId must contain only letters, digits, dot, underscore, or hyphen")
    if not 1 <= top_k <= 20:
        raise RuntimeError("topK must be between 1 and 20")
    if timeout_seconds <= 0 or retries < 0 or retry_delay_seconds < 0:
        raise RuntimeError("timeout and retry settings must not be negative")

    endpoint = base_url.rstrip("/") + DEFAULT_ENDPOINT
    cases = load_cases(dataset_path)
    transcripts_dir = output_dir / "transcripts"
    results: list[dict[str, Any]] = []

    for benchmark_case in cases:
        transcript_path = transcripts_dir / f"{benchmark_case.case_id}.json"
        if resume and transcript_path.is_file():
            results.append({"caseId": benchmark_case.case_id, "status": "SKIPPED", "file": str(transcript_path)})
            continue

        started = time.monotonic()
        error_message: str | None = None
        for attempt in range(retries + 1):
            try:
                transcript = request_transcript(
                    endpoint, token, run_id, benchmark_case.case_id, top_k,
                    dataset_version, timeout_seconds, opener
                )
                write_json(transcript_path, transcript)
                results.append({
                    "caseId": benchmark_case.case_id,
                    "status": "COMPLETED",
                    "attempts": attempt + 1,
                    "durationSeconds": round(time.monotonic() - started, 3),
                    "file": str(transcript_path),
                })
                break
            except BenchmarkRequestError as error:
                error_message = str(error)
                if not error.retryable or attempt == retries:
                    results.append({
                        "caseId": benchmark_case.case_id,
                        "status": "FAILED",
                        "attempts": attempt + 1,
                        "durationSeconds": round(time.monotonic() - started, 3),
                        "error": error_message,
                    })
                    break
                sleeper(retry_delay_seconds * (attempt + 1))

    metadata = {
        "schemaVersion": "rag-benchmark-run-v1",
        "runId": run_id,
        "dataset": str(dataset_path),
        "datasetVersion": dataset_version,
        "endpoint": endpoint,
        "topK": top_k,
        "generatedAt": datetime.now(UTC).isoformat(),
        "caseCount": len(cases),
        "completedCount": sum(item["status"] == "COMPLETED" for item in results),
        "failedCount": sum(item["status"] == "FAILED" for item in results),
        "skippedCount": sum(item["status"] == "SKIPPED" for item in results),
        "cases": results,
    }
    if metadata["failedCount"] and not metadata["completedCount"]:
        metadata["collectionStatus"] = "FAILED"
    elif metadata["failedCount"]:
        metadata["collectionStatus"] = "PARTIAL"
    else:
        metadata["collectionStatus"] = "COMPLETE"
    metadata["transcriptCount"] = write_transcripts_jsonl(output_dir, results)
    write_json(output_dir / "1-2-runtime-metadata.json", metadata)
    return metadata


def main() -> int:
    args = parse_args()
    output_dir = args.output_dir or Path("target") / "rag-benchmark" / args.run_id
    manifest = run_benchmark(
        base_url=args.base_url,
        run_id=args.run_id,
        dataset_path=args.dataset,
        output_dir=output_dir,
        top_k=args.top_k,
        dataset_version=args.dataset_version,
        timeout_seconds=args.timeout_seconds,
        retries=args.retries,
        retry_delay_seconds=args.retry_delay_seconds,
        resume=args.resume,
        token=require_benchmark_token(),
    )
    print(json.dumps({
        "runId": manifest["runId"],
        "collectionStatus": manifest["collectionStatus"],
        "caseCount": manifest["caseCount"],
        "completedCount": manifest["completedCount"],
        "failedCount": manifest["failedCount"],
        "output": str(output_dir),
    }, ensure_ascii=False))
    return collection_exit_code(manifest)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"Benchmark run failed: {type(error).__name__}: {error}", file=sys.stderr)
        raise SystemExit(1)
