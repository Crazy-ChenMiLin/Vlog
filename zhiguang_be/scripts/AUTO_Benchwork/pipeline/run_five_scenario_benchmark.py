#!/usr/bin/env python3
"""Run the reviewed five-scenario suite through the deployed Benchmark API."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path


HERE = Path(__file__).resolve().parent
BENCHMARK_ROOT = HERE.parent
DATASETS = BENCHMARK_ROOT / "datasets" / "five_scenario"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--suite", type=Path, default=DATASETS / "suite-v1.json")
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--skip-judge", action="store_true")
    return parser.parse_args()


def invoke(arguments: list[str]) -> None:
    completed = subprocess.run([sys.executable, *arguments], check=False)
    if completed.returncode != 0:
        raise RuntimeError(f"Command failed with exit code {completed.returncode}: {' '.join(arguments)}")


def main() -> int:
    args = parse_args()
    suite = json.loads(args.suite.read_text(encoding="utf-8"))
    args.output_dir.mkdir(parents=True, exist_ok=True)
    for scenario in suite["scenarios"]:
        scenario_dir = args.output_dir / scenario["slug"]
        dataset = DATASETS / scenario["dataset"]
        invoke([
            str(HERE / "benchmark.py"), "--base-url", args.base_url,
            "--run-id", f"{args.run_id}-{scenario['slug']}", "--dataset", str(dataset),
            "--dataset-version", scenario["dataset_version"], "--output-dir", str(scenario_dir),
            "--top-k", str(args.top_k),
        ])
        invoke([str(HERE / "report_generator.py"), "--run-dir", str(scenario_dir)])
        if not args.skip_judge:
            invoke([
                str(HERE / "judge.py"), "--run-dir", str(scenario_dir),
                "--dataset", str(dataset),
            ])
    if not args.skip_judge:
        invoke([
            str(HERE / "aggregate_five_scenario_reports.py"),
            "--suite", str(args.suite), "--run-dir", str(args.output_dir),
        ])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
