#!/usr/bin/env python3
"""Run all reviewed Benchmark scenarios without letting one failure stop the rest."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable


HERE = Path(__file__).resolve().parent
BENCHMARK_ROOT = HERE.parent
DATASETS = BENCHMARK_ROOT / "datasets" / "five_scenario"
STATUS_JSON = "five-scenario-run-status.json"
STATUS_MARKDOWN = "five-scenario-run-status.md"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--suite", type=Path, default=DATASETS / "suite-v1.json")
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--skip-judge", action="store_true")
    return parser.parse_args()


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


def invoke(
    phase: str,
    arguments: list[str],
    runner: Callable[..., subprocess.CompletedProcess[Any]] = subprocess.run,
) -> dict[str, Any]:
    try:
        completed = runner([sys.executable, *arguments], check=False)
        exit_code = int(completed.returncode)
        return {
            "phase": phase,
            "status": "SUCCESS" if exit_code == 0 else "FAILED",
            "exitCode": exit_code,
        }
    except OSError as error:
        return {
            "phase": phase,
            "status": "FAILED",
            "exitCode": None,
            "error": f"{type(error).__name__}: {error}",
        }


def scenario_execution_status(phases: list[dict[str, Any]]) -> str:
    statuses = [phase["status"] for phase in phases]
    if statuses and all(status == "SUCCESS" for status in statuses):
        return "COMPLETE"
    if statuses and all(status == "FAILED" for status in statuses):
        return "FAILED"
    return "PARTIAL"


def status_markdown(document: dict[str, Any]) -> str:
    lines = [
        "# Five-scenario execution status",
        "",
        f"- Run ID: `{document['runId']}`",
        f"- Overall execution: `{document['overallStatus']}`",
        "",
        "| Scenario | Execution | Collection | Report | Judge |",
        "| --- | --- | --- | --- | --- |",
    ]
    for scenario in document["scenarios"]:
        phases = {phase["phase"]: phase["status"] for phase in scenario["phases"]}
        lines.append(
            f"| {scenario['topic']} | {scenario['executionStatus']} | "
            f"{phases.get('collection', 'SKIPPED')} | {phases.get('report', 'SKIPPED')} | "
            f"{phases.get('judge', 'SKIPPED')} |"
        )
    lines.extend([
        "",
        "A failed scenario does not stop later scenarios. Inspect its directory in the Artifact for per-case diagnostics.",
    ])
    return "\n".join(lines) + "\n"


def write_status(output_dir: Path, document: dict[str, Any]) -> None:
    document["updatedAt"] = now()
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / STATUS_JSON).write_text(
        json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    (output_dir / STATUS_MARKDOWN).write_text(
        status_markdown(document), encoding="utf-8", newline="\n"
    )


def execute_suite(
    args: argparse.Namespace,
    runner: Callable[..., subprocess.CompletedProcess[Any]] = subprocess.run,
) -> dict[str, Any]:
    suite = json.loads(args.suite.read_text(encoding="utf-8"))
    scenarios = suite.get("scenarios")
    if not isinstance(scenarios, list) or len(scenarios) != 5:
        raise RuntimeError("The reviewed suite must contain exactly five scenarios")

    document: dict[str, Any] = {
        "schemaVersion": "rag-benchmark-five-scenario-execution-v1",
        "runId": args.run_id,
        "suite": str(args.suite),
        "startedAt": now(),
        "overallStatus": "RUNNING",
        "scenarios": [],
    }
    write_status(args.output_dir, document)

    for scenario in scenarios:
        scenario_dir = args.output_dir / scenario["slug"]
        dataset = DATASETS / scenario["dataset"]
        record: dict[str, Any] = {
            "order": scenario["order"],
            "slug": scenario["slug"],
            "topic": scenario["topic"],
            "datasetVersion": scenario["dataset_version"],
            "dataset": str(dataset),
            "outputDirectory": str(scenario_dir),
            "executionStatus": "RUNNING",
            "phases": [],
        }
        document["scenarios"].append(record)
        write_status(args.output_dir, document)

        record["phases"].append(invoke("collection", [
            str(HERE / "benchmark.py"),
            "--base-url", args.base_url,
            "--run-id", f"{args.run_id}-{scenario['slug']}",
            "--dataset", str(dataset),
            "--dataset-version", scenario["dataset_version"],
            "--output-dir", str(scenario_dir),
            "--top-k", str(args.top_k),
        ], runner))
        write_status(args.output_dir, document)

        record["phases"].append(invoke("report", [
            str(HERE / "report_generator.py"),
            "--run-dir", str(scenario_dir),
        ], runner))
        write_status(args.output_dir, document)

        if not args.skip_judge:
            record["phases"].append(invoke("judge", [
                str(HERE / "judge.py"),
                "--run-dir", str(scenario_dir),
                "--dataset", str(dataset),
            ], runner))

        record["executionStatus"] = scenario_execution_status(record["phases"])
        write_status(args.output_dir, document)

    document["overallStatus"] = (
        "COMPLETE"
        if all(item["executionStatus"] == "COMPLETE" for item in document["scenarios"])
        else "PARTIAL"
    )
    write_status(args.output_dir, document)

    aggregate_phase = invoke("aggregate", [
        str(HERE / "aggregate_five_scenario_reports.py"),
        "--suite", str(args.suite),
        "--run-dir", str(args.output_dir),
    ], runner)
    document["aggregate"] = aggregate_phase
    if aggregate_phase["status"] != "SUCCESS":
        document["overallStatus"] = "PARTIAL"
    document["finishedAt"] = now()
    write_status(args.output_dir, document)
    return document


def main() -> int:
    args = parse_args()
    document = execute_suite(args)
    print(json.dumps({
        "overallStatus": document["overallStatus"],
        "status": str(args.output_dir / STATUS_JSON),
        "report": str(args.output_dir / "five-scenario-report.json"),
    }, ensure_ascii=False))
    return 0 if document["overallStatus"] == "COMPLETE" else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"Five-scenario Benchmark failed: {type(error).__name__}: {error}", file=sys.stderr)
        raise SystemExit(1)
