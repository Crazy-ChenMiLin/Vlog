#!/usr/bin/env python3
"""Aggregate all five scenarios while keeping missing or partial results visible."""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


METRICS = ("hitAt5", "recallAt5", "mrrAt5", "correctness", "completeness", "groundedness")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite", type=Path, required=True)
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--json-output", type=Path)
    parser.add_argument("--markdown-output", type=Path)
    return parser.parse_args()


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def read_json_optional(path: Path) -> dict[str, Any] | None:
    try:
        value = read_json(path)
        return value if isinstance(value, dict) else None
    except (OSError, json.JSONDecodeError):
        return None


def nested_number(document: dict[str, Any] | None, *keys: str) -> float | None:
    value: Any = document
    for key in keys:
        if not isinstance(value, dict):
            return None
        value = value.get(key)
    return float(value) if isinstance(value, (int, float)) else None


def scenario_metrics(
    root: Path,
    scenario: dict[str, Any],
    execution: dict[str, Any] | None = None,
) -> dict[str, Any]:
    scenario_dir = root / scenario["slug"]
    runtime = read_json_optional(scenario_dir / "1-2-runtime-metadata.json")
    funnel = read_json_optional(scenario_dir / "report" / "2-1-funnel-report.json")
    judge = read_json_optional(scenario_dir / "3-1-judge-report.json")
    metrics = {
        "hitAt5": nested_number(funnel, "stages", "RERANKED", "goldHitRate"),
        "recallAt5": nested_number(funnel, "stages", "RERANKED", "macroRecallAtK"),
        "mrrAt5": nested_number(funnel, "stages", "RERANKED", "meanReciprocalRank"),
        "correctness": nested_number(judge, "dimensionAverages", "correctness"),
        "completeness": nested_number(judge, "dimensionAverages", "completeness"),
        "groundedness": nested_number(judge, "dimensionAverages", "groundedness"),
    }
    values = [metrics[name] for name in METRICS]
    scenario_score = round(sum(values) / len(values), 6) if all(value is not None for value in values) else None
    collection_status = runtime.get("collectionStatus", "MISSING") if runtime else "MISSING"
    judge_status = judge.get("evaluationStatus", "MISSING") if judge else "MISSING"
    execution_status = execution.get("executionStatus", "UNKNOWN") if execution else "UNKNOWN"
    result_status = (
        "COMPLETE"
        if scenario_score is not None
        and collection_status == "COMPLETE"
        and judge_status == "COMPLETE"
        and execution_status in {"COMPLETE", "UNKNOWN"}
        else "PARTIAL"
    )
    return {
        **scenario,
        "resultStatus": result_status,
        "executionStatus": execution_status,
        "collectionStatus": collection_status,
        "judgeStatus": judge_status,
        "judgeCompleted": judge.get("completedCount", 0) if judge else 0,
        "judgeSkipped": judge.get("skippedNotEvaluableCount", 0) if judge else 0,
        "phases": execution.get("phases", []) if execution else [],
        "metrics": metrics,
        "scenarioScore": scenario_score,
    }


def average_available(scenarios: list[dict[str, Any]], metric: str) -> dict[str, Any]:
    values = [item["metrics"][metric] for item in scenarios if item["metrics"][metric] is not None]
    return {
        "value": round(sum(values) / len(values), 6) if values else None,
        "scenarioCount": len(values),
    }


def aggregate(suite: dict[str, Any], run_dir: Path) -> dict[str, Any]:
    definitions = suite.get("scenarios", [])
    if len(definitions) != 5:
        raise RuntimeError(f"Exactly five scenarios are required, got {len(definitions)}")
    execution_document = read_json_optional(run_dir / "five-scenario-run-status.json") or {}
    execution_by_slug = {
        item.get("slug"): item
        for item in execution_document.get("scenarios", [])
        if isinstance(item, dict)
    }
    scenarios = [
        scenario_metrics(run_dir, scenario, execution_by_slug.get(scenario["slug"]))
        for scenario in definitions
    ]
    available_macro = {metric: average_available(scenarios, metric) for metric in METRICS}
    macro = {
        metric: available_macro[metric]["value"]
        if available_macro[metric]["scenarioCount"] == 5
        else None
        for metric in METRICS
    }
    scores = [item["scenarioScore"] for item in scenarios]
    overall_score = round(sum(scores) / 5, 6) if all(score is not None for score in scores) else None
    complete_count = sum(item["resultStatus"] == "COMPLETE" for item in scenarios)
    return {
        "schemaVersion": "rag-benchmark-five-scenario-report-v2",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "reportStatus": "COMPLETE" if complete_count == 5 else "PARTIAL",
        "scenarioCount": 5,
        "completeScenarioCount": complete_count,
        "caseCount": 200,
        "scoringDefinition": "Every scenario has equal 20% weight. The official overall score is emitted only when all five scenarios have all six metrics.",
        "macroMetrics": macro,
        "availableMacroMetrics": available_macro,
        "overallScore": overall_score,
        "scenarios": scenarios,
    }


def cell(value: Any) -> str:
    return "-" if value is None else f"{value:.4f}" if isinstance(value, float) else str(value)


def markdown(report: dict[str, Any]) -> str:
    lines = [
        "# Five-scenario RAG Benchmark",
        "",
        f"- Report status: `{report['reportStatus']}`",
        f"- Complete scenarios: {report['completeScenarioCount']} / 5",
        "- Cases: 200",
        f"- Official overall score: **{cell(report['overallScore'])}**",
        "- Weighting: every scenario contributes exactly 20%; missing metrics never get silently reweighted.",
        "",
        "| Scenario | Status | Hit@5 | Recall@5 | MRR@5 | Correct | Complete | Grounded | Score |",
        "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for item in report["scenarios"]:
        metrics = item["metrics"]
        lines.append(
            f"| {item['topic']} | {item['resultStatus']} | {cell(metrics['hitAt5'])} | "
            f"{cell(metrics['recallAt5'])} | {cell(metrics['mrrAt5'])} | "
            f"{cell(metrics['correctness'])} | {cell(metrics['completeness'])} | "
            f"{cell(metrics['groundedness'])} | {cell(item['scenarioScore'])} |"
        )
    macro = report["macroMetrics"]
    lines.extend([
        f"| **Five-scenario macro** | **{report['reportStatus']}** | **{cell(macro['hitAt5'])}** | "
        f"**{cell(macro['recallAt5'])}** | **{cell(macro['mrrAt5'])}** | "
        f"**{cell(macro['correctness'])}** | **{cell(macro['completeness'])}** | "
        f"**{cell(macro['groundedness'])}** | **{cell(report['overallScore'])}** |",
        "",
        "## Execution details",
        "",
        "| Scenario | Execution | Collection | Judge |",
        "| --- | --- | --- | --- |",
    ])
    for item in report["scenarios"]:
        lines.append(
            f"| {item['topic']} | {item['executionStatus']} | {item['collectionStatus']} | {item['judgeStatus']} |"
        )
    lines.extend([
        "",
        "Per-scenario JSON, Markdown and failed-case diagnostics remain available in the Artifact.",
    ])
    return "\n".join(lines) + "\n"


def main() -> int:
    args = parse_args()
    report = aggregate(read_json(args.suite), args.run_dir)
    json_output = args.json_output or args.run_dir / "five-scenario-report.json"
    markdown_output = args.markdown_output or args.run_dir / "five-scenario-report.md"
    json_output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    markdown_output.write_text(markdown(report), encoding="utf-8", newline="\n")
    print(json.dumps({
        "reportStatus": report["reportStatus"],
        "overallScore": report["overallScore"],
        "json": str(json_output),
        "markdown": str(markdown_output),
    }))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
