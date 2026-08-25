#!/usr/bin/env python3
"""Aggregate five completed scenario runs with equal scenario weights."""

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


def scenario_metrics(root: Path, scenario: dict[str, Any]) -> dict[str, Any]:
    scenario_dir = root / scenario["slug"]
    funnel = read_json(scenario_dir / "report" / "2-1-funnel-report.json")
    judge = read_json(scenario_dir / "3-1-judge-report.json")
    reranked = funnel["stages"]["RERANKED"]
    dimensions = judge["dimensionAverages"]
    metrics = {
        "hitAt5": reranked["goldHitRate"],
        "recallAt5": reranked["macroRecallAtK"],
        "mrrAt5": reranked["meanReciprocalRank"],
        "correctness": dimensions["correctness"],
        "completeness": dimensions["completeness"],
        "groundedness": dimensions["groundedness"],
    }
    if any(not isinstance(metrics[name], (int, float)) for name in METRICS):
        raise RuntimeError(f"Scenario {scenario['slug']} has an incomplete metric set")
    return {
        **scenario,
        "collectionStatus": read_json(scenario_dir / "1-2-runtime-metadata.json")["collectionStatus"],
        "judgeStatus": judge["evaluationStatus"],
        "judgeCompleted": judge["completedCount"],
        "judgeSkipped": judge.get("skippedNotEvaluableCount", 0),
        "metrics": metrics,
        "scenarioScore": round(sum(metrics.values()) / len(METRICS), 6),
    }


def aggregate(suite: dict[str, Any], run_dir: Path) -> dict[str, Any]:
    scenarios = [scenario_metrics(run_dir, scenario) for scenario in suite["scenarios"]]
    if len(scenarios) != 5:
        raise RuntimeError(f"Exactly five scenarios are required, got {len(scenarios)}")
    macro = {
        metric: round(sum(item["metrics"][metric] for item in scenarios) / len(scenarios), 6)
        for metric in METRICS
    }
    return {
        "schemaVersion": "rag-benchmark-five-scenario-report-v1",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "scenarioCount": len(scenarios),
        "caseCount": len(scenarios) * 40,
        "scoringDefinition": "Each scenario has equal 20% weight. Each scenarioScore is the arithmetic mean of six normalized metrics; macro metrics remain visible separately.",
        "macroMetrics": macro,
        "overallScore": round(sum(item["scenarioScore"] for item in scenarios) / len(scenarios), 6),
        "scenarios": scenarios,
    }


def markdown(report: dict[str, Any]) -> str:
    lines = [
        "# Five-scenario RAG Benchmark",
        "",
        f"- Scenarios: {report['scenarioCount']}",
        f"- Cases: {report['caseCount']}",
        f"- Overall score: **{report['overallScore']:.4f}**",
        "- Weighting: every scenario contributes exactly 20%.",
        "",
        "| Scenario | Hit@5 | Recall@5 | MRR@5 | Correct | Complete | Grounded | Score |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for item in report["scenarios"]:
        m = item["metrics"]
        lines.append(
            f"| {item['topic']} | {m['hitAt5']:.4f} | {m['recallAt5']:.4f} | {m['mrrAt5']:.4f} | "
            f"{m['correctness']:.4f} | {m['completeness']:.4f} | {m['groundedness']:.4f} | {item['scenarioScore']:.4f} |"
        )
    m = report["macroMetrics"]
    lines.extend([
        f"| **Macro average** | **{m['hitAt5']:.4f}** | **{m['recallAt5']:.4f}** | **{m['mrrAt5']:.4f}** | **{m['correctness']:.4f}** | **{m['completeness']:.4f}** | **{m['groundedness']:.4f}** | **{report['overallScore']:.4f}** |",
        "",
        "The aggregate never replaces per-scenario results; a weak domain remains visible in the table.",
    ])
    return "\n".join(lines) + "\n"


def main() -> int:
    args = parse_args()
    report = aggregate(read_json(args.suite), args.run_dir)
    json_output = args.json_output or args.run_dir / "five-scenario-report.json"
    markdown_output = args.markdown_output or args.run_dir / "five-scenario-report.md"
    json_output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    markdown_output.write_text(markdown(report), encoding="utf-8", newline="\n")
    print(json.dumps({"overallScore": report["overallScore"], "json": str(json_output), "markdown": str(markdown_output)}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
