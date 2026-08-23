from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parents[2] / "AUTO_Benchwork"
sys.path.insert(0, str(SCRIPT_DIR))

import relabel_gold_expected_chunk_ids as relabel  # noqa: E402


class FakeResponse:
    def __init__(self, payload: dict[str, object]) -> None:
        self.payload = payload

    def __enter__(self) -> "FakeResponse":
        return self

    def __exit__(self, exc_type, exc_value, traceback) -> None:
        return None

    def read(self) -> bytes:
        return json.dumps(self.payload).encode("utf-8")


def hit(chunk_id: str, title: str, content: str, score: float = 1.0) -> dict[str, object]:
    post_id, position = chunk_id.split("#")
    return {
        "_score": score,
        "_source": {
            "content": content,
            "metadata": {
                "chunkId": chunk_id,
                "postId": post_id,
                "position": int(position),
                "title": title,
                "sectionTitle": "核心概念",
            },
        },
    }


class GoldRelabellingProposalTest(unittest.TestCase):
    def test_exact_evidence_is_auto_matched_and_lexical_result_requires_review(self) -> None:
        dataset = [
            {
                "id": "gold-001",
                "question": "HyDE 的作用是什么？",
                "expected_chunk_ids": ["old-post#1"],
                "evidence": {"title": "HyDE 原文", "excerpt": "HyDE 生成假设答案用于检索。"},
            },
            {
                "id": "gold-002",
                "question": "RRF 如何融合结果？",
                "expected_chunk_ids": ["old-post#2"],
                "evidence": {"title": "已经删除的标题", "excerpt": "RRF 使用排名融合。"},
            },
        ]

        def opener(request, timeout):
            body = json.loads(request.data.decode("utf-8"))
            if "term" in body["query"]:
                title = body["query"]["term"]["metadata.title.keyword"]
                if title == "HyDE 原文":
                    return FakeResponse({"hits": {"hits": [
                        hit("new-post#3", title, "## 核心概念\nHyDE 生成假设答案用于检索。"),
                    ]}})
                return FakeResponse({"hits": {"hits": []}})
            return FakeResponse({"hits": {"hits": [
                hit("candidate-post#0", "新的 RRF 文章", "RRF 会按照排名合并候选结果。", 7.5),
            ]}})

        proposal = relabel.build_proposal(
            dataset=dataset,
            es_url="http://es.example:9200",
            index="zhiguang-ai-index",
            top_k=3,
            timeout_seconds=5,
            opener=opener,
        )

        self.assertEqual({"AUTO_MATCHED": 1, "REVIEW_REQUIRED": 1, "UNRESOLVED": 0}, proposal["statusCounts"])
        self.assertEqual("AUTO_MATCHED", proposal["cases"][0]["status"])
        self.assertEqual(["new-post#3"], proposal["cases"][0]["proposedExpectedChunkIds"])
        self.assertEqual("REVIEW_REQUIRED", proposal["cases"][1]["status"])
        self.assertEqual([], proposal["cases"][1]["proposedExpectedChunkIds"])
        self.assertEqual(["candidate-post#0"], proposal["cases"][1]["reviewCandidateChunkIds"])
        self.assertEqual("candidate-post#0", proposal["cases"][1]["candidates"][0]["chunkId"])
        self.assertIn("Review rule", relabel.proposal_markdown(proposal))

    def test_unresolved_case_never_invents_a_chunk_id(self) -> None:
        dataset = [{
            "id": "gold-003",
            "question": "不存在的问题",
            "expected_chunk_ids": ["old#1"],
            "evidence": {"title": "不存在的文章", "excerpt": "不存在的证据"},
        }]

        def opener(request, timeout):
            return FakeResponse({"hits": {"hits": []}})

        proposal = relabel.build_proposal(
            dataset, "http://es.example", "index", 3, 5, opener
        )

        self.assertEqual("UNRESOLVED", proposal["cases"][0]["status"])
        self.assertEqual([], proposal["cases"][0]["proposedExpectedChunkIds"])


if __name__ == "__main__":
    unittest.main()
