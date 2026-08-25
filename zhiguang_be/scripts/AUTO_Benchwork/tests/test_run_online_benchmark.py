from __future__ import annotations

import json
import io
import sys
import tempfile
import unittest
import urllib.error
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parents[1] / "pipeline"
sys.path.insert(0, str(SCRIPT_DIR))

import benchmark as runner  # noqa: E402


class FakeResponse:
    def __init__(self, payload: dict[str, object]) -> None:
        self.payload = payload

    def __enter__(self) -> "FakeResponse":
        return self

    def __exit__(self, exc_type, exc_value, traceback) -> None:
        return None

    def read(self) -> bytes:
        return json.dumps(self.payload).encode("utf-8")


class RunOnlineBenchmarkTest(unittest.TestCase):
    def test_runs_each_dataset_case_and_writes_numbered_runtime_artifacts(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            dataset = root / "gold.json"
            dataset.write_text(json.dumps([
                {"id": "gold-001"},
                {"id": "gold-002"},
            ]), encoding="utf-8")
            calls: list[dict[str, object]] = []

            def opener(request, timeout):
                body = json.loads(request.data.decode("utf-8"))
                calls.append({"body": body, "token": request.get_header("X-benchmark-token"), "timeout": timeout})
                return FakeResponse({"traceId": body["caseId"], "status": "COMPLETED"})

            output = root / "result"
            metadata = runner.run_benchmark(
                base_url="https://benchmark.example/",
                run_id="ci-run-001",
                dataset_path=dataset,
                output_dir=output,
                top_k=5,
                dataset_version="t2-history-culture-v1",
                timeout_seconds=30,
                retries=0,
                retry_delay_seconds=0,
                resume=False,
                token="not-printed-token",
                opener=opener,
                sleeper=lambda _: None,
            )

            self.assertEqual(2, metadata["completedCount"])
            self.assertEqual(0, metadata["failedCount"])
            self.assertEqual("COMPLETE", metadata["collectionStatus"])
            self.assertEqual(0, runner.collection_exit_code(metadata))
            self.assertEqual(2, metadata["transcriptCount"])
            self.assertEqual(["gold-001", "gold-002"], [call["body"]["caseId"] for call in calls])
            self.assertTrue(all(call["body"]["datasetVersion"] == "t2-history-culture-v1" for call in calls))
            self.assertTrue(all(call["token"] == "not-printed-token" for call in calls))
            self.assertTrue((output / "transcripts" / "gold-001.json").is_file())
            self.assertTrue((output / "transcripts" / "gold-002.json").is_file())
            self.assertEqual("rag-benchmark-run-v1", json.loads((output / "1-2-runtime-metadata.json").read_text())["schemaVersion"])
            self.assertEqual(2, len((output / "1-1-transcripts.jsonl").read_text(encoding="utf-8").splitlines()))

    def test_rejects_duplicate_case_ids_before_calling_the_backend(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            dataset = Path(temp) / "gold.json"
            dataset.write_text(json.dumps([
                {"id": "gold-001"},
                {"id": "gold-001"},
            ]), encoding="utf-8")

            with self.assertRaisesRegex(RuntimeError, "duplicate caseId"):
                runner.load_cases(dataset)

    def test_records_a_failed_case_but_preserves_completed_transcripts(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            dataset = root / "gold.json"
            dataset.write_text(json.dumps([
                {"id": "gold-001"},
                {"id": "gold-002"},
            ]), encoding="utf-8")

            def opener(request, timeout):
                case_id = json.loads(request.data.decode("utf-8"))["caseId"]
                if case_id == "gold-002":
                    raise urllib.error.HTTPError(
                        request.full_url, 503, "service unavailable", {}, io.BytesIO(b"temporary failure")
                    )
                return FakeResponse({"traceId": case_id, "status": "COMPLETED"})

            output = root / "result"
            metadata = runner.run_benchmark(
                base_url="https://benchmark.example",
                run_id="ci-run-002",
                dataset_path=dataset,
                output_dir=output,
                top_k=5,
                dataset_version="t2-history-culture-v1",
                timeout_seconds=30,
                retries=0,
                retry_delay_seconds=0,
                resume=False,
                token="not-printed-token",
                opener=opener,
                sleeper=lambda _: None,
            )

            self.assertEqual(1, metadata["completedCount"])
            self.assertEqual(1, metadata["failedCount"])
            self.assertEqual("PARTIAL", metadata["collectionStatus"])
            self.assertEqual(0, runner.collection_exit_code(metadata))
            self.assertEqual(1, metadata["transcriptCount"])
            self.assertTrue((output / "transcripts" / "gold-001.json").is_file())
            self.assertFalse((output / "transcripts" / "gold-002.json").exists())

    def test_total_collection_failure_is_fatal(self) -> None:
        self.assertEqual(1, runner.collection_exit_code({"collectionStatus": "FAILED"}))


if __name__ == "__main__":
    unittest.main()
