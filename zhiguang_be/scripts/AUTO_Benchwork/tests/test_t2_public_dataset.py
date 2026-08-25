import importlib.util
import json
import sys
import unittest
from pathlib import Path


BENCHMARK_ROOT = Path(__file__).resolve().parents[1]
TOOLS_ROOT = BENCHMARK_ROOT / "tools" / "t2"
DATASETS_ROOT = BENCHMARK_ROOT / "datasets"
sys.path.insert(0, str(TOOLS_ROOT))


def load_module(name: str):
    spec = importlib.util.spec_from_file_location(name, TOOLS_ROOT / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    assert spec and spec.loader
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


importer = load_module("import_t2_corpus")
evaluator = load_module("evaluate_t2_retrieval")
embedder = load_module("embed_t2_corpus")
topic_gold = load_module("build_t2_topic_gold")


class T2CorpusImportTest(unittest.TestCase):
    def test_source_document_preserves_t2_id_and_text(self):
        document = importer.source_document("doc-17", "raw text", "title")

        self.assertEqual("doc-17", document["id"])
        self.assertEqual("raw text", document["content"])
        self.assertEqual("doc-17", document["metadata"]["chunkId"])
        self.assertEqual("mteb/T2Retrieval", document["metadata"]["dataset"])

    def test_embedding_batches_respect_item_and_character_limits(self):
        documents = [
            {"id": "a", "content": "a" * 4000},
            {"id": "b", "content": "b" * 4000},
            {"id": "c", "content": "c" * 4000},
            {"id": "d", "content": "d"},
        ]

        batches = embedder.embedding_batches(documents, max_items=8, max_chars=9000)

        self.assertEqual([["a", "b"], ["c", "d"]], [[item["id"] for item in batch] for batch in batches])

    def test_scroll_slice_is_added_only_for_parallel_workers(self):
        self.assertNotIn("slice", embedder.search_body(64, False))
        self.assertEqual({"id": 2, "max": 4}, embedder.search_body(64, False, 2, 4)["slice"])


class T2EvaluationTest(unittest.TestCase):
    def test_metrics_include_recall_hit_and_bounded_mrr(self):
        metrics = evaluator.metrics_for(["x", "relevant", "z"], {"relevant", "other"}, [1, 3])

        self.assertEqual(0.5, metrics["mrr@3"])
        self.assertEqual(0.0, metrics["recall@1"])
        self.assertEqual(0.5, metrics["recall@3"])
        self.assertEqual(1.0, metrics["hit@3"])

    def test_rrf_fusion_is_deterministic(self):
        ranking = evaluator.rrf_fuse([["a", "b"], ["b", "c"]], size=3, rrf_k=60)

        self.assertEqual(["b", "a", "c"], ranking)

    def test_query_sampling_is_seeded(self):
        rows = [{"_id": str(i), "text": f"q-{i}"} for i in range(10)]
        relevant = {str(i): {f"d-{i}"} for i in range(10)}

        first = evaluator.select_queries(rows, relevant, 4, 123)
        second = evaluator.select_queries(rows, relevant, 4, 123)

        self.assertEqual(first, second)
        self.assertEqual(4, len(first))


class T2TopicGoldTest(unittest.TestCase):
    def test_five_scenario_suite_points_to_five_reviewed_gold_files(self):
        suite_root = DATASETS_ROOT / "five_scenario"
        suite = json.loads((suite_root / "suite-v1.json").read_text(encoding="utf-8"))

        self.assertEqual(5, len(suite["scenarios"]))
        for scenario in suite["scenarios"]:
            gold_path = suite_root / scenario["dataset"]
            self.assertTrue(gold_path.is_file(), gold_path)
            self.assertEqual(40, len(json.loads(gold_path.read_text(encoding="utf-8"))))

    def test_approved_automotive_gold_is_valid(self):
        gold = json.loads(
            (DATASETS_ROOT / "five_scenario" / "automotive-maintenance" / "gold-v1.json")
            .read_text(encoding="utf-8")
        )

        self.assertEqual(40, len(gold))
        self.assertEqual(40, len({case["id"] for case in gold}))
        self.assertEqual({"approved"}, {case["status"] for case in gold})
        self.assertEqual({"汽车维护与故障诊断"}, {case["scenario_tags"][1] for case in gold})

    def test_topic_scoring_prefers_computer_topic(self):
        scores = topic_gold.topic_scores("MySQL 数据库如何优化慢查询？")

        self.assertGreater(scores["计算机与互联网"], 0)

    def test_question_quality_rejects_download_queries(self):
        self.assertLess(topic_gold.question_quality("数据库教程在哪里下载？", 2), 0)

    def test_similarity_detects_near_duplicates(self):
        score = topic_gold.similarity("汽车轮胎应该如何保养", "汽车轮胎如何进行保养")

        self.assertGreater(score, 0.5)

    def test_best_evidence_prefers_text_matching_the_question(self):
        item = {"question": "发动机机油乳化是什么原因", "expected_chunk_ids": ["a", "b"]}
        evidence = {
            "a": {"excerpt": "天气与旅游信息"},
            "b": {"excerpt": "发动机机油乳化通常与冷却液混入有关"},
        }

        self.assertEqual("b", topic_gold.best_evidence_id(item, evidence))


if __name__ == "__main__":
    unittest.main()
