from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parents[2] / "AUTO_Benchwork"
sys.path.insert(0, str(SCRIPT_DIR))

import judge  # noqa: E402
import report_generator  # noqa: E402


def transcript(case_id: str, status: str, original_hit: bool, original_ranks: list[int]) -> dict[str, object]:
    return {
        "traceId": f"trace-{case_id}",
        "status": status,
        "finalAnswer": "answer" if status == "COMPLETED" else None,
        "evaluation": {
            "runId": "ci-run-001",
            "caseId": case_id,
            "expectedChunkIds": ["chunk-1"],
        },
        "stages": [
            {
                "stage": "ORIGINAL",
                "candidates": [{"id": "chunk-1"}],
                "goldHit": original_hit,
                "goldRanks": original_ranks,
            },
            {
                "stage": "RERANKED",
                "candidates": [{"id": "chunk-2"}],
                "goldHit": False,
                "goldRanks": [],
            },
        ],
    }


class FakeResponse:
    def __init__(self, payload: dict[str, object]) -> None:
        self.payload = payload

    def __enter__(self) -> "FakeResponse":
        return self

    def __exit__(self, exc_type, exc_value, traceback) -> None:
        return None

    def read(self) -> bytes:
        return json.dumps(self.payload).encode("utf-8")


class BenchmarkReportsTest(unittest.TestCase):
    def test_judge_loads_reranked_contexts_from_elasticsearch_in_rank_order(self) -> None:
        value = transcript("gold-001", "COMPLETED", True, [1])
        value["stages"][-1]["candidates"] = [{"chunkId": "b"}, {"chunkId": "a"}]

        def opener(request, timeout):
            body = json.loads(request.data.decode("utf-8"))
            self.assertEqual(["b", "a"], body["ids"])
            return FakeResponse({"docs": [
                {"_id": "a", "found": True, "_source": {"content": "A", "metadata": {"title": "TA"}}},
                {"_id": "b", "found": True, "_source": {"content": "B", "metadata": {"title": "TB"}}},
            ]})

        contexts = judge.load_retrieved_contexts(value, "http://es", "index", opener)

        self.assertEqual(["b", "a"], [context["chunkId"] for context in contexts])
        self.assertEqual(["B", "A"], [context["content"] for context in contexts])

    def test_judge_requests_json_mode_and_reports_truncation(self) -> None:
        def opener(request, timeout):
            body = json.loads(request.data.decode("utf-8"))
            self.assertEqual({"type": "json_object"}, body["response_format"])
            self.assertEqual(2048, body["max_tokens"])
            return FakeResponse({
                "choices": [{"finish_reason": "length", "message": {"content": '{"verdict":"PASS"'}}],
                "usage": {"completion_tokens": 800},
            })

        with self.assertRaises(judge.JudgeRequestError) as caught:
            judge.call_judge("https://judge.example/chat/completions", "token", "model", "prompt", 5, opener)

        self.assertIn("truncated", str(caught.exception))
        self.assertIn("finishReason", caught.exception.raw_response)

    def test_stage_metrics_use_common_top_k_and_exclude_empty_hyde_stages(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            run_dir = Path(temp) / "run"
            transcripts = run_dir / "transcripts"
            transcripts.mkdir(parents=True)
            value = transcript("gold-001", "COMPLETED", True, [6])
            value["stages"].insert(1, {
                "stage": "HYDE", "candidates": [], "goldHit": False, "goldRanks": [],
            })
            value["stages"][0]["candidates"] = [
                {"id": f"other-{index}"} for index in range(5)
            ] + [{"id": "chunk-1"}]
            (transcripts / "gold-001.json").write_text(json.dumps(value), encoding="utf-8")
            (run_dir / "1-2-runtime-metadata.json").write_text(
                json.dumps({"runId": "ci-run-001", "topK": 5}), encoding="utf-8"
            )

            report = report_generator.build_report(run_dir)

            self.assertEqual(0.0, report["stages"]["ORIGINAL"]["goldHitRate"])
            self.assertEqual(1.0, report["stages"]["ORIGINAL"]["fullStageGoldHitRate"])
            self.assertEqual(0, report["stages"]["HYDE"]["executedCaseCount"])
            self.assertEqual(1, report["stages"]["HYDE"]["skippedEmptyCandidateCount"])
            self.assertIsNone(report["stages"]["HYDE"]["goldHitRate"])

    def test_partial_collection_builds_hit_at_k_and_baseline_delta(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            run_dir = Path(temp) / "run"
            transcripts = run_dir / "transcripts"
            transcripts.mkdir(parents=True)
            (transcripts / "gold-001.json").write_text(
                json.dumps(transcript("gold-001", "COMPLETED", True, [2])), encoding="utf-8"
            )
            (transcripts / "gold-002.json").write_text(
                json.dumps(transcript("gold-002", "FAILED", False, [])), encoding="utf-8"
            )
            baseline = Path(temp) / "baseline.json"
            baseline.write_text(json.dumps({
                "schemaVersion": report_generator.REPORT_SCHEMA_VERSION,
                "runId": "reviewed-baseline",
                "stages": {
                    "ORIGINAL": {"goldHitRate": 0.5, "meanReciprocalRank": 0.25},
                    "RERANKED": {"goldHitRate": 0.25, "meanReciprocalRank": 0.25},
                },
            }), encoding="utf-8")

            report = report_generator.build_report(run_dir)
            diff = report_generator.build_diff_report(report, baseline)

            self.assertEqual(1, report["completedCaseCount"])
            self.assertEqual(1, report["nonCompletedCaseCount"])
            self.assertEqual(1.0, report["stages"]["ORIGINAL"]["goldHitRate"])
            self.assertEqual(0.5, report["stages"]["ORIGINAL"]["meanReciprocalRank"])
            self.assertIn("Hit@K", report["metricDefinition"])
            self.assertEqual("ALIGNED", report["goldIdAudit"]["status"])
            self.assertEqual(1, report["goldIdAudit"]["caseCountWithAnyMatch"])
            self.assertEqual(["gold-001"], report["goldIdAudit"]["casesWithAnyMatch"])
            self.assertEqual(0, report["goldIdAudit"]["annotationMismatchCount"])
            self.assertEqual("COMPARISON_AVAILABLE", diff["status"])
            self.assertEqual(0.5, diff["stages"]["ORIGINAL"]["goldHitRateDelta"])
            self.assertEqual(0.25, diff["stages"]["ORIGINAL"]["meanReciprocalRankDelta"])

    def test_zero_successful_cases_still_produces_a_machine_report(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            run_dir = Path(temp) / "run"
            run_dir.mkdir()
            (run_dir / "1-2-runtime-metadata.json").write_text(
                json.dumps({"runId": "ci-run-empty"}), encoding="utf-8"
            )

            report = report_generator.build_report(run_dir)

            self.assertEqual("ci-run-empty", report["runId"])
            self.assertEqual(0, report["inputTranscriptCount"])
            self.assertEqual(0, report["completedCaseCount"])
            self.assertEqual({}, report["stages"])
            self.assertEqual("NO_COMPLETED_CASES", report["goldIdAudit"]["status"])

    def test_gold_id_audit_exposes_expected_and_online_candidate_id_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            run_dir = Path(temp) / "run"
            transcripts = run_dir / "transcripts"
            transcripts.mkdir(parents=True)
            mismatched = transcript("gold-001", "COMPLETED", False, [])
            mismatched["stages"][0]["candidates"] = [{"chunkId": "online-chunk-999"}]
            (transcripts / "gold-001.json").write_text(json.dumps(mismatched), encoding="utf-8")

            report = report_generator.build_report(run_dir)

            self.assertEqual("NO_MATCH", report["goldIdAudit"]["status"])
            self.assertEqual(["gold-001"], report["goldIdAudit"]["casesWithoutAnyMatch"])
            self.assertEqual(["chunk-1"], report["goldIdAudit"]["unmatchedExpectedChunkIds"])
            self.assertIn("online-chunk-999", report["goldIdAudit"]["observedCandidateChunkIdSamples"])
            self.assertEqual(
                ["online-chunk-999"],
                report["cases"][0]["stages"]["ORIGINAL"]["candidateChunkIds"],
            )

    def test_judge_accepts_strict_json_and_can_summarize_an_empty_partial_run(self) -> None:
        def opener(request, timeout):
            return FakeResponse({"choices": [{"finish_reason": "stop", "message": {"content": '{"verdict":"PASS","score":1,"correctness":1,"completeness":1,"groundedness":1,"reason":"证据一致"}'}}]})

        judgement = judge.call_judge(
            "https://judge.example/chat/completions", "token", "model", "prompt", 5, opener
        )
        self.assertEqual("PASS", judgement["verdict"])
        self.assertEqual(1.0, judgement["correctness"])
        self.assertEqual("stop", judgement["providerMeta"]["finishReason"])

        with tempfile.TemporaryDirectory() as temp:
            run_dir = Path(temp) / "run"
            dataset = Path(temp) / "gold.json"
            dataset.write_text(json.dumps([
                {"id": "gold-001", "question": "q", "evidence": {"excerpt": "e"}},
            ]), encoding="utf-8")
            document = judge.run_judgements(
                run_dir=run_dir,
                dataset_path=dataset,
                output_path=run_dir / "3-1-judge-report.json",
                base_url="https://judge.example",
                model="model",
                api_key="token",
                timeout_seconds=5,
                retries=0,
                retry_delay_seconds=0,
                resume=False,
                opener=opener,
                sleeper=lambda _: None,
            )
            summary = judge.benchmark_summary(
                {"runId": "ci-run-empty", "caseCount": 1, "completedCount": 0, "failedCount": 1, "skippedCount": 0, "topK": 5},
                {"stages": {}},
                {"status": "NO_BASELINE"},
                document,
            )

            self.assertEqual(0, document["completedCount"])
            self.assertTrue((run_dir / "3-1-judge-report.json").is_file())
            self.assertIn("first 5 candidates", summary)
            self.assertIn("No baseline is configured", summary)

    def test_judge_retries_non_json_and_retains_raw_response(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            run_dir = Path(temp) / "run"
            transcripts = run_dir / "transcripts"
            transcripts.mkdir(parents=True)
            (transcripts / "gold-001.json").write_text(
                json.dumps(transcript("gold-001", "COMPLETED", True, [1])), encoding="utf-8"
            )
            dataset = Path(temp) / "gold.json"
            dataset.write_text(json.dumps([
                {"id": "gold-001", "question": "q", "evidence": {"excerpt": "e"}},
            ]), encoding="utf-8")
            contents = iter([
                "这次没有按照要求返回 JSON",
                '{"verdict":"PASS","score":1,"correctness":1,"completeness":1,"groundedness":1,"reason":"证据一致"}',
            ])

            def opener(request, timeout):
                return FakeResponse({"choices": [{"finish_reason": "stop", "message": {"content": next(contents)}}]})

            document = judge.run_judgements(
                run_dir=run_dir,
                dataset_path=dataset,
                output_path=run_dir / "3-1-judge-report.json",
                base_url="https://judge.example",
                model="model",
                api_key="token",
                timeout_seconds=5,
                retries=1,
                retry_delay_seconds=0,
                resume=False,
                opener=opener,
                sleeper=lambda _: None,
            )

            self.assertEqual("COMPLETE", document["evaluationStatus"])
            self.assertEqual(1, document["completedCount"])
            self.assertEqual(1, document["retryFailureCount"])
            self.assertEqual(2, document["results"][0]["attempts"])
            self.assertEqual(
                "这次没有按照要求返回 JSON",
                document["results"][0]["retryFailures"][0]["rawResponse"],
            )

    def test_partial_judge_report_is_non_fatal_but_total_judge_failure_is_fatal(self) -> None:
        common = (Path("run"), Path("gold.json"), "https://judge.example/chat/completions", "model")
        partial = judge.judgement_document(*common, [
            {"caseId": "gold-001", "status": "COMPLETED", "verdict": "PASS"},
            {"caseId": "gold-002", "status": "FAILED", "attemptFailures": []},
        ])
        failed = judge.judgement_document(*common, [
            {"caseId": "gold-001", "status": "FAILED", "attemptFailures": []},
        ])
        complete_with_planned_skip = judge.judgement_document(*common, [
            {"caseId": "gold-001", "status": "COMPLETED", "verdict": "PASS"},
            {"caseId": "gold-002", "status": "SKIPPED_NOT_EVALUABLE"},
        ])

        self.assertEqual("PARTIAL", partial["evaluationStatus"])
        self.assertEqual(0, judge.judge_exit_code(partial))
        self.assertEqual("FAILED", failed["evaluationStatus"])
        self.assertEqual(1, judge.judge_exit_code(failed))
        self.assertEqual("COMPLETE", complete_with_planned_skip["evaluationStatus"])
        self.assertEqual(1, complete_with_planned_skip["skippedNotEvaluableCount"])


if __name__ == "__main__":
    unittest.main()
