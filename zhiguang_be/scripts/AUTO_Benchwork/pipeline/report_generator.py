#!/usr/bin/env python3
"""Create an auditable retrieval report from Benchmark Full Transcripts.

This script never calls the backend and never judges answer correctness.  It
only aggregates the Gold annotations that Java attached to each Transcript
during the real RAG run.  Answer quality is intentionally left to judge.py.
"""

from __future__ import annotations

import argparse
import json
import os
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


REPORT_SCHEMA_VERSION = "rag-benchmark-report-v2"
CANONICAL_STAGE_ORDER = ("ORIGINAL", "HYDE", "KEYWORD", "FUSED", "RERANKED")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Aggregate saved Benchmark Transcripts into machine-readable JSON reports."
    )
    parser.add_argument(
        "--run-dir",
        required=True,
        type=Path,
        help="Directory created by benchmark.py; it must contain transcripts/*.json",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=None,
        help="Defaults to <run-dir>/report",
    )
    parser.add_argument(
        "--baseline",
        type=Path,
        default=None,
        help="Optional previous funnel-report.json used to calculate metric deltas.",
    )
    return parser.parse_args()


def read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise RuntimeError(f"Cannot read Transcript {path}: {error}") from error
    if not isinstance(value, dict):
        raise RuntimeError(f"Transcript {path} must contain a JSON object")
    return value


def require_string(value: Any, field: str, source: Path) -> str:
    if not isinstance(value, str) or not value.strip():
        raise RuntimeError(f"Transcript {source} has no non-empty {field}")
    return value


def rounded_ratio(numerator: int, denominator: int) -> float | None:
    return round(numerator / denominator, 6) if denominator else None


def unique_strings(values: list[str]) -> list[str]:
    return list(dict.fromkeys(values))


def candidate_chunk_id(candidate: dict[str, Any]) -> str | None:
    for field in ("chunkId", "id"):
        value = candidate.get(field)
        if isinstance(value, str) and value.strip():
            return value
    return None


def transcript_case_row(transcript: dict[str, Any], source: Path) -> dict[str, Any]:
    evaluation = transcript.get("evaluation")
    if not isinstance(evaluation, dict):
        raise RuntimeError(f"Transcript {source} has no evaluation annotation")

    case_id = require_string(evaluation.get("caseId"), "evaluation.caseId", source)
    status = require_string(transcript.get("status"), "status", source)
    stages = transcript.get("stages")
    if not isinstance(stages, list):
        raise RuntimeError(f"Transcript {source} has no stages array")
    raw_expected_chunk_ids = evaluation.get("expectedChunkIds", [])
    if not isinstance(raw_expected_chunk_ids, list) or any(
        not isinstance(chunk_id, str) or not chunk_id.strip() for chunk_id in raw_expected_chunk_ids
    ):
        raise RuntimeError(f"Transcript {source} has invalid evaluation.expectedChunkIds")
    expected_chunk_ids = unique_strings(raw_expected_chunk_ids)

    stage_rows: dict[str, dict[str, Any]] = {}
    for stage in stages:
        if not isinstance(stage, dict):
            raise RuntimeError(f"Transcript {source} contains a non-object stage")
        name = require_string(stage.get("stage"), "stages[].stage", source)
        if name in stage_rows:
            raise RuntimeError(f"Transcript {source} contains duplicate stage {name}")

        candidates = stage.get("candidates")
        if not isinstance(candidates, list):
            raise RuntimeError(f"Transcript {source} stage {name} has no candidates array")
        if any(not isinstance(candidate, dict) for candidate in candidates):
            raise RuntimeError(f"Transcript {source} stage {name} contains a non-object candidate")
        candidate_chunk_ids = unique_strings([
            chunk_id
            for candidate in candidates
            if (chunk_id := candidate_chunk_id(candidate)) is not None
        ])
        candidate_chunk_id_set = set(candidate_chunk_ids)
        matched_expected_chunk_ids = [
            chunk_id for chunk_id in expected_chunk_ids if chunk_id in candidate_chunk_id_set
        ]
        raw_ranks = stage.get("goldRanks")
        if raw_ranks is None:
            raw_ranks = []
        if not isinstance(raw_ranks, list) or any(
            not isinstance(rank, int) or rank < 1 for rank in raw_ranks
        ):
            raise RuntimeError(f"Transcript {source} stage {name} has invalid goldRanks")

        gold_hit = stage.get("goldHit")
        if gold_hit not in (True, False, None):
            raise RuntimeError(f"Transcript {source} stage {name} has invalid goldHit")
        if gold_hit is True and not raw_ranks:
            raise RuntimeError(f"Transcript {source} stage {name} says goldHit but has no goldRanks")
        if gold_hit is False and raw_ranks:
            raise RuntimeError(f"Transcript {source} stage {name} has goldRanks but says no goldHit")

        stage_rows[name] = {
            "candidateCount": len(candidates),
            "candidateChunkIdCount": len(candidate_chunk_ids),
            "candidateChunkIds": candidate_chunk_ids,
            "candidateChunkIdSamples": candidate_chunk_ids[:10],
            "goldHit": gold_hit is True,
            "goldRanks": raw_ranks,
            "bestGoldRank": min(raw_ranks) if raw_ranks else None,
            "matchedExpectedChunkIds": matched_expected_chunk_ids,
            "derivedGoldHit": bool(matched_expected_chunk_ids),
            "annotationConsistent": gold_hit is None or (gold_hit is True) == bool(matched_expected_chunk_ids),
        }

    observed_candidate_chunk_ids = unique_strings([
        chunk_id
        for stage in stage_rows.values()
        for chunk_id in stage["candidateChunkIds"]
    ])
    matched_expected_chunk_ids = [
        chunk_id
        for chunk_id in expected_chunk_ids
        if any(chunk_id in stage["matchedExpectedChunkIds"] for stage in stage_rows.values())
    ]
    final_answer = transcript.get("finalAnswer")
    has_answer = isinstance(final_answer, str) and bool(final_answer.strip())
    return {
        "caseId": case_id,
        "traceId": transcript.get("traceId"),
        "status": status,
        "hasAnswer": has_answer,
        "expectedChunkIds": expected_chunk_ids,
        "goldIdAudit": {
            "matchedExpectedChunkIds": matched_expected_chunk_ids,
            "unmatchedExpectedChunkIds": [
                chunk_id for chunk_id in expected_chunk_ids if chunk_id not in set(matched_expected_chunk_ids)
            ],
            "anyMatch": bool(matched_expected_chunk_ids),
            "observedCandidateChunkIdCount": len(observed_candidate_chunk_ids),
            "observedCandidateChunkIdSamples": observed_candidate_chunk_ids[:10],
        },
        "stages": stage_rows,
        "source": str(source),
    }


def ordered_stage_names(case_rows: list[dict[str, Any]]) -> list[str]:
    present = {name for row in case_rows for name in row["stages"]}
    return [name for name in CANONICAL_STAGE_ORDER if name in present] + sorted(
        present.difference(CANONICAL_STAGE_ORDER)
    )


def build_gold_id_audit(completed_rows: list[dict[str, Any]]) -> dict[str, Any]:
    rows_with_expected_ids = [row for row in completed_rows if row["expectedChunkIds"]]
    cases_with_matches = [row["caseId"] for row in rows_with_expected_ids if row["goldIdAudit"]["anyMatch"]]
    cases_without_matches = [row["caseId"] for row in rows_with_expected_ids if not row["goldIdAudit"]["anyMatch"]]
    expected_chunk_ids = unique_strings([
        chunk_id for row in rows_with_expected_ids for chunk_id in row["expectedChunkIds"]
    ])
    matched_expected_chunk_ids = unique_strings([
        chunk_id
        for row in rows_with_expected_ids
        for chunk_id in row["goldIdAudit"]["matchedExpectedChunkIds"]
    ])
    observed_candidate_chunk_ids = unique_strings([
        chunk_id
        for row in completed_rows
        for stage in row["stages"].values()
        for chunk_id in stage["candidateChunkIds"]
    ])
    annotation_mismatch_cases = sorted({
        row["caseId"]
        for row in completed_rows
        for stage in row["stages"].values()
        if not stage["annotationConsistent"]
    })
    if not completed_rows:
        status = "NO_COMPLETED_CASES"
    elif not rows_with_expected_ids:
        status = "NO_EXPECTED_IDS"
    elif not cases_without_matches:
        status = "ALIGNED"
    elif not cases_with_matches:
        status = "NO_MATCH"
    else:
        status = "PARTIAL_MATCH"
    return {
        "status": status,
        "completedCaseCount": len(completed_rows),
        "caseCountWithExpectedIds": len(rows_with_expected_ids),
        "caseCountWithAnyMatch": len(cases_with_matches),
        "caseCountWithoutAnyMatch": len(cases_without_matches),
        "casesWithAnyMatch": cases_with_matches,
        "casesWithoutAnyMatch": cases_without_matches,
        "caseMatchRate": rounded_ratio(len(cases_with_matches), len(rows_with_expected_ids)),
        "expectedChunkIdCount": len(expected_chunk_ids),
        "matchedExpectedChunkIdCount": len(matched_expected_chunk_ids),
        "unmatchedExpectedChunkIdCount": len(expected_chunk_ids) - len(matched_expected_chunk_ids),
        "unmatchedExpectedChunkIds": [
            chunk_id for chunk_id in expected_chunk_ids if chunk_id not in set(matched_expected_chunk_ids)
        ],
        "observedCandidateChunkIdCount": len(observed_candidate_chunk_ids),
        "observedCandidateChunkIdSamples": observed_candidate_chunk_ids[:20],
        "annotationMismatchCount": len(annotation_mismatch_cases),
        "annotationMismatchCases": annotation_mismatch_cases,
        "note": "Expected IDs are compared directly with deployed Transcript candidate chunkId values. A mismatch can be a retrieval miss or Gold/index ID drift.",
    }


def build_report(run_dir: Path) -> dict[str, Any]:
    transcripts_dir = run_dir / "transcripts"
    transcript_paths = sorted(transcripts_dir.glob("*.json"))
    case_rows = [transcript_case_row(read_json(path), path) for path in transcript_paths]
    case_ids = [row["caseId"] for row in case_rows]
    if len(case_ids) != len(set(case_ids)):
        raise RuntimeError("Duplicate evaluation.caseId values found in saved Transcripts")

    completed_rows = [row for row in case_rows if row["status"] == "COMPLETED"]
    runtime_metadata_path = run_dir / "1-2-runtime-metadata.json"
    runtime_metadata = read_json(runtime_metadata_path) if runtime_metadata_path.is_file() else {}
    top_k = runtime_metadata.get("topK", 5)
    if not isinstance(top_k, int) or top_k < 1:
        raise RuntimeError(f"Runtime metadata has invalid topK: {top_k}")
    stage_summary: dict[str, dict[str, Any]] = {}
    for stage_name in ordered_stage_names(completed_rows):
        available_rows = [row for row in completed_rows if stage_name in row["stages"]]
        executed_rows = [row for row in available_rows if row["stages"][stage_name]["candidateCount"] > 0]
        top_k_best_ranks: list[int] = []
        recall_values: list[float] = []
        full_stage_best_ranks: list[int] = []
        for row in executed_rows:
            stage = row["stages"][stage_name]
            top_k_ranks = [rank for rank in stage["goldRanks"] if rank <= top_k]
            stage["topK"] = top_k
            stage["topKGoldRanks"] = top_k_ranks
            stage["topKGoldHit"] = bool(top_k_ranks)
            stage["topKBestGoldRank"] = min(top_k_ranks) if top_k_ranks else None
            if top_k_ranks:
                top_k_best_ranks.append(min(top_k_ranks))
            if stage["bestGoldRank"] is not None:
                full_stage_best_ranks.append(stage["bestGoldRank"])
            expected_count = len(row["expectedChunkIds"])
            matched_in_top_k = sum(rank <= top_k for rank in stage["goldRanks"])
            recall_values.append(matched_in_top_k / expected_count if expected_count else 0.0)

        top_k_hits = len(top_k_best_ranks)
        full_stage_hits = len(full_stage_best_ranks)
        reciprocal_rank_sum = sum(1 / rank for rank in top_k_best_ranks)
        full_stage_reciprocal_rank_sum = sum(1 / rank for rank in full_stage_best_ranks)
        stage_summary[stage_name] = {
            "topK": top_k,
            "availableCaseCount": len(available_rows),
            "executedCaseCount": len(executed_rows),
            "skippedEmptyCandidateCount": len(available_rows) - len(executed_rows),
            "missingCaseCount": len(completed_rows) - len(available_rows),
            "executionRate": rounded_ratio(len(executed_rows), len(completed_rows)),
            "goldHitCount": top_k_hits,
            "goldHitRate": rounded_ratio(top_k_hits, len(executed_rows)),
            "macroRecallAtK": round(sum(recall_values) / len(recall_values), 6) if recall_values else None,
            "meanReciprocalRank": round(reciprocal_rank_sum / len(executed_rows), 6) if executed_rows else None,
            "meanBestGoldRankWhenHit": round(sum(top_k_best_ranks) / len(top_k_best_ranks), 6)
            if top_k_best_ranks else None,
            "fullStageGoldHitCount": full_stage_hits,
            "fullStageGoldHitRate": rounded_ratio(full_stage_hits, len(executed_rows)),
            "fullStageMeanReciprocalRank": round(full_stage_reciprocal_rank_sum / len(executed_rows), 6)
            if executed_rows else None,
        }

    non_empty_answers = sum(row["hasAnswer"] for row in completed_rows)
    run_ids = {
        read_json(Path(row["source"])).get("evaluation", {}).get("runId")
        for row in case_rows
    }
    run_ids.discard(None)
    if len(run_ids) > 1:
        raise RuntimeError(f"Saved Transcripts belong to multiple runs: {sorted(run_ids)}")

    if not run_ids and isinstance(runtime_metadata.get("runId"), str):
        run_ids.add(runtime_metadata["runId"])

    return {
        "schemaVersion": REPORT_SCHEMA_VERSION,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "runId": next(iter(run_ids), None),
        "runDirectory": str(run_dir),
        "inputTranscriptCount": len(case_rows),
        "completedCaseCount": len(completed_rows),
        "nonCompletedCaseCount": len(case_rows) - len(completed_rows),
        "answer": {
            "nonEmptyCount": non_empty_answers,
            "nonEmptyRate": rounded_ratio(non_empty_answers, len(completed_rows)),
            "note": "This is only an answer-presence check; judge.py owns answer-quality scoring.",
        },
        "metricDefinition": (
            f"Hit@K (goldHitRate), Recall@K (macroRecallAtK), and MRR@K (meanReciprocalRank) "
            f"use the first {top_k} candidates only. "
            "Empty candidate stages are SKIPPED and excluded from the stage-quality denominator; executionRate reports their frequency."
        ),
        "goldIdAudit": build_gold_id_audit(completed_rows),
        "stages": stage_summary,
        "cases": sorted(case_rows, key=lambda row: row["caseId"]),
    }


def write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    file_descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.", suffix=".tmp", dir=path.parent, text=True
    )
    try:
        with os.fdopen(file_descriptor, "w", encoding="utf-8", newline="\n") as temporary:
            temporary.write(content)
        Path(temporary_name).replace(path)
    except Exception:
        Path(temporary_name).unlink(missing_ok=True)
        raise


def metric_delta(current: Any, baseline: Any) -> float | None:
    if not isinstance(current, (int, float)) or not isinstance(baseline, (int, float)):
        return None
    return round(current - baseline, 6)


def build_diff_report(report: dict[str, Any], baseline_path: Path | None) -> dict[str, Any]:
    if baseline_path is None:
        return {
            "schemaVersion": "rag-benchmark-diff-v2",
            "status": "NO_BASELINE",
            "currentRunId": report["runId"],
            "message": "No baseline was supplied; this first run establishes an inspectable report only.",
            "stages": {},
        }

    baseline = read_json(baseline_path)
    if baseline.get("schemaVersion") != REPORT_SCHEMA_VERSION:
        raise RuntimeError(
            f"Baseline {baseline_path} uses {baseline.get('schemaVersion')}; expected {REPORT_SCHEMA_VERSION}. "
            "Create a new reviewed baseline after the Top-K metric correction."
        )
    baseline_stages = baseline.get("stages")
    if not isinstance(baseline_stages, dict):
        raise RuntimeError(f"Baseline {baseline_path} has no stages object")

    stages: dict[str, dict[str, Any]] = {}
    for stage_name, current_stage in report["stages"].items():
        previous_stage = baseline_stages.get(stage_name, {})
        if not isinstance(previous_stage, dict):
            previous_stage = {}
        stages[stage_name] = {
            "goldHitRateDelta": metric_delta(
                current_stage.get("goldHitRate"), previous_stage.get("goldHitRate")
            ),
            "meanReciprocalRankDelta": metric_delta(
                current_stage.get("meanReciprocalRank"), previous_stage.get("meanReciprocalRank")
            ),
        }
    return {
        "schemaVersion": "rag-benchmark-diff-v2",
        "status": "COMPARISON_AVAILABLE",
        "currentRunId": report["runId"],
        "baselineRunId": baseline.get("runId"),
        "baseline": str(baseline_path),
        "stages": stages,
    }


def main() -> int:
    args = parse_args()
    report = build_report(args.run_dir)
    diff = build_diff_report(report, args.baseline)
    output_dir = args.output_dir or args.run_dir / "report"
    write_text(output_dir / "2-1-funnel-report.json", json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    write_text(output_dir / "2-3-diff-report.json", json.dumps(diff, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({
        "runId": report["runId"],
        "completedCaseCount": report["completedCaseCount"],
        "output": str(output_dir),
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"Report generation failed: {type(error).__name__}: {error}")
        raise SystemExit(1)
