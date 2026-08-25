#!/usr/bin/env python3
"""Build a deterministic, reviewable topic-oriented Gold set from T2Retrieval."""

from __future__ import annotations

import argparse
import html
import json
import re
from collections import defaultdict
from difflib import SequenceMatcher
from pathlib import Path
from typing import Any


DEFAULT_ROOT = Path("target/rag-benchmark/public-datasets/t2retrieval/raw")
TOPICS: list[tuple[str, tuple[str, ...]]] = [
    (
        "计算机与互联网",
        (
            "计算机", "互联网", "软件", "程序", "编程", "代码", "数据库", "算法", "网络",
            "服务器", "操作系统", "人工智能", "机器学习", "java", "python", "linux", "mysql",
        ),
    ),
    (
        "法律与社会规则",
        (
            "法律", "法规", "违法", "犯罪", "合同", "赔偿", "责任", "诉讼", "法院", "律师",
            "劳动法", "刑法", "民法", "行政处罚", "知识产权", "婚姻法",
        ),
    ),
    (
        "医疗与健康",
        (
            "健康", "疾病", "症状", "治疗", "药物", "医院", "医生", "手术", "感染", "血压",
            "糖尿病", "癌症", "怀孕", "营养", "心理", "过敏",
        ),
    ),
    (
        "教育与考试",
        (
            "教育", "学校", "学生", "教师", "考试", "高考", "中考", "大学", "课程", "学习",
            "招生", "专业", "学历", "毕业", "论文", "培训",
        ),
    ),
    (
        "历史与传统文化",
        (
            "历史", "古代", "朝代", "皇帝", "诗人", "诗句", "成语", "传统文化", "文物", "名著",
            "唐朝", "宋朝", "明朝", "清朝", "书法", "节日",
        ),
    ),
    (
        "财经与商业",
        (
            "经济", "金融", "股票", "基金", "银行", "保险", "投资", "贷款", "税", "企业",
            "公司", "市场", "商业", "财务", "会计", "货币",
        ),
    ),
    (
        "交通与汽车",
        (
            "交通", "汽车", "火车", "铁路", "高铁", "飞机", "航空", "地铁", "公交", "驾驶",
            "驾照", "车辆", "发动机", "轮胎", "高速公路", "车站",
        ),
    ),
    (
        "生活与饮食",
        (
            "生活", "饮食", "食品", "食物", "做法", "烹饪", "蔬菜", "水果", "家常菜", "营养",
            "保存", "清洗", "家居", "装修", "衣服", "旅游",
        ),
    ),
]

QUESTION_CUES = ("什么", "如何", "怎么", "为什么", "哪些", "区别", "作用", "规定", "方法", "多少", "是否", "原因")
LOW_QUALITY_MARKERS = ("下载", "图片", "壁纸", "视频", "小说全文", "联系电话", "官网", "哪里有卖", "多少钱")
TAG_RE = re.compile(r"<[^>]*>")
SPACE_RE = re.compile(r"\s+")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--queries", type=Path, default=DEFAULT_ROOT / "queries.parquet")
    parser.add_argument("--qrels", type=Path, default=DEFAULT_ROOT / "qrels.parquet")
    parser.add_argument("--corpus", type=Path, default=DEFAULT_ROOT / "corpus.parquet")
    parser.add_argument("--per-topic", type=int, default=5)
    parser.add_argument("--manifest", type=Path, help="Optional reviewed single-topic query-ID manifest.")
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("target/rag-benchmark/generated/t2-topic-gold.json"),
    )
    parser.add_argument(
        "--markdown-output",
        type=Path,
        default=Path("target/rag-benchmark/generated/t2-topic-gold.md"),
    )
    args = parser.parse_args()
    if args.per_topic < 1:
        parser.error("--per-topic must be positive")
    return args


def read_parquet(path: Path) -> Any:
    try:
        import pyarrow.parquet as parquet
    except ImportError as error:
        raise RuntimeError("pyarrow is required") from error
    if not path.is_file():
        raise RuntimeError(f"Parquet file not found: {path}")
    return parquet.read_table(path)


def clean_text(value: Any) -> str:
    text = html.unescape(str(value or ""))
    text = TAG_RE.sub(" ", text)
    return SPACE_RE.sub(" ", text).strip()


def topic_scores(question: str) -> dict[str, int]:
    lowered = question.lower()
    scores: dict[str, int] = {}
    for topic, keywords in TOPICS:
        matches = [keyword for keyword in keywords if keyword.lower() in lowered]
        if matches:
            scores[topic] = sum(max(2, len(keyword)) for keyword in matches)
    return scores


def question_quality(question: str, relevant_count: int) -> int:
    length = len(question)
    if length < 8 or length > 80 or any(marker in question.lower() for marker in LOW_QUALITY_MARKERS):
        return -1000
    score = 0
    if 12 <= length <= 45:
        score += 8
    elif length <= 60:
        score += 4
    if any(cue in question for cue in QUESTION_CUES):
        score += 8
    if question.endswith(("？", "?")):
        score += 2
    if 2 <= relevant_count <= 8:
        score += 5
    elif relevant_count == 1:
        score += 2
    elif relevant_count > 15:
        score -= 8
    score -= question.count("_") * 2
    return score


def ngrams(text: str, size: int = 2) -> set[str]:
    compact = re.sub(r"\W+", "", text.lower())
    return {compact[index : index + size] for index in range(max(0, len(compact) - size + 1))}


def similarity(left: str, right: str) -> float:
    left_grams = ngrams(left)
    right_grams = ngrams(right)
    if not left_grams or not right_grams:
        return 0.0
    jaccard = len(left_grams & right_grams) / len(left_grams | right_grams)
    sequence = SequenceMatcher(None, left.lower(), right.lower()).ratio()
    return max(jaccard, sequence)


def select_cases(
    query_rows: list[dict[str, Any]],
    relevant: dict[str, list[str]],
    per_topic: int,
) -> list[dict[str, Any]]:
    candidates: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in query_rows:
        query_id = str(row.get("_id"))
        question = clean_text(row.get("text"))
        expected_ids = relevant.get(query_id, [])
        if not expected_ids:
            continue
        scores = topic_scores(question)
        if not scores:
            continue
        topic = max(scores, key=lambda name: (scores[name], name))
        quality = question_quality(question, len(expected_ids))
        if quality < 0:
            continue
        candidates[topic].append(
            {
                "query_id": query_id,
                "question": question,
                "expected_chunk_ids": expected_ids,
                "topic": topic,
                "selection_score": quality + scores[topic],
            }
        )

    selected: list[dict[str, Any]] = []
    for topic, _ in TOPICS:
        topic_selected: list[dict[str, Any]] = []
        ordered = sorted(
            candidates.get(topic, []),
            key=lambda item: (-item["selection_score"], len(item["question"]), item["query_id"]),
        )
        for candidate in ordered:
            if any(similarity(candidate["question"], previous["question"]) >= 0.55 for previous in topic_selected):
                continue
            topic_selected.append(candidate)
            if len(topic_selected) == per_topic:
                break
        if len(topic_selected) < per_topic:
            raise RuntimeError(f"Topic {topic} has only {len(topic_selected)} usable questions")
        selected.extend(topic_selected)
    return selected


def load_evidence(corpus_path: Path, wanted_ids: set[str]) -> dict[str, dict[str, str]]:
    try:
        import pyarrow.parquet as parquet
    except ImportError as error:
        raise RuntimeError("pyarrow is required") from error
    evidence: dict[str, dict[str, str]] = {}
    parquet_file = parquet.ParquetFile(corpus_path)
    for batch in parquet_file.iter_batches(batch_size=2048, columns=["_id", "title", "text"]):
        for row in batch.to_pylist():
            corpus_id = str(row["_id"])
            if corpus_id not in wanted_ids:
                continue
            evidence[corpus_id] = {
                "title": clean_text(row.get("title")),
                "excerpt": clean_text(row.get("text"))[:400],
            }
        if len(evidence) == len(wanted_ids):
            break
    return evidence


def select_manifest_cases(
    manifest_path: Path,
    query_rows: list[dict[str, Any]],
    relevant: dict[str, list[str]],
) -> list[dict[str, Any]]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    topic = clean_text(manifest.get("topic"))
    answer_references: dict[str, Any] = {}
    reference_file = manifest.get("answer_reference_file")
    if isinstance(reference_file, str) and reference_file.strip():
        reference_path = manifest_path.parent / reference_file
        reference_document = json.loads(reference_path.read_text(encoding="utf-8"))
        raw_references = reference_document.get("cases")
        if not isinstance(raw_references, dict):
            raise RuntimeError("Answer reference file requires a cases object")
        answer_references = raw_references
    manifest_cases = manifest.get("cases")
    query_ids = manifest.get("query_ids")
    default_status = clean_text(manifest.get("status") or "candidate")
    if isinstance(manifest_cases, list) and manifest_cases:
        entries = manifest_cases
    elif isinstance(query_ids, list) and query_ids:
        entries = [{"query_id": query_id} for query_id in query_ids]
    else:
        raise RuntimeError("Topic manifest requires a topic and non-empty cases or query_ids")
    if not topic:
        raise RuntimeError("Topic manifest requires a non-empty topic")
    normalized_ids = [str(entry.get("query_id")) for entry in entries]
    if len(normalized_ids) != len(set(normalized_ids)):
        raise RuntimeError("Topic manifest contains duplicate query IDs")
    query_by_id = {str(row.get("_id")): clean_text(row.get("text")) for row in query_rows}
    selected: list[dict[str, Any]] = []
    for entry, query_id in zip(entries, normalized_ids):
        source_question = query_by_id.get(query_id)
        expected_ids = relevant.get(query_id)
        if not source_question:
            raise RuntimeError(f"Manifest query does not exist or has empty text: {query_id}")
        if not expected_ids:
            raise RuntimeError(f"Manifest query has no positive qrels: {query_id}")
        question = clean_text(entry.get("question") or source_question)
        evidence_id = str(entry.get("review_evidence_chunk_id") or "")
        if evidence_id and evidence_id not in expected_ids:
            raise RuntimeError(f"Pinned evidence {evidence_id} is not a positive qrel for query {query_id}")
        selected.append(
            {
                "query_id": query_id,
                "question": question,
                "source_question": source_question,
                "expected_chunk_ids": expected_ids,
                "topic": topic,
                "selection_score": None,
                "review_evidence_chunk_id": evidence_id or None,
                "status": clean_text(entry.get("status") or default_status),
                "answer_reference": answer_references.get(query_id, {}),
            }
        )
    return selected


def evidence_overlap(question: str, excerpt: str) -> float:
    question_grams = ngrams(question)
    excerpt_grams = ngrams(excerpt)
    if not question_grams:
        return 0.0
    return len(question_grams & excerpt_grams) / len(question_grams)


def best_evidence_id(item: dict[str, Any], evidence: dict[str, dict[str, str]]) -> str:
    if item.get("review_evidence_chunk_id"):
        return str(item["review_evidence_chunk_id"])
    expected_ids = item["expected_chunk_ids"]
    return max(
        expected_ids,
        key=lambda corpus_id: (
            evidence_overlap(item["question"], evidence.get(corpus_id, {}).get("excerpt", "")),
            corpus_id,
        ),
    )


def build_gold(selected: list[dict[str, Any]], evidence: dict[str, dict[str, str]]) -> list[dict[str, Any]]:
    gold: list[dict[str, Any]] = []
    for number, item in enumerate(selected, start=1):
        evidence_id = best_evidence_id(item, evidence)
        first_evidence = evidence.get(evidence_id, {})
        answer_reference = item.get("answer_reference") or {}
        reference_points = answer_reference.get("reference_points", [])
        if not isinstance(reference_points, list) or any(not isinstance(point, str) for point in reference_points):
            raise RuntimeError(f"Invalid reference_points for query {item['query_id']}")
        reference_answer = clean_text(answer_reference.get("reference_answer"))
        if not reference_answer and reference_points:
            reference_answer = "；".join(clean_text(point) for point in reference_points) + "。"
        gold.append(
            {
                "id": f"gold-{number:03d}",
                "question": item["question"],
                "expected_chunk_ids": item["expected_chunk_ids"],
                "scenario_tags": ["T2Retrieval", item["topic"], "专题检索"],
                "evidence": {
                    "title": first_evidence.get("title") or f"T2Retrieval corpus {evidence_id}",
                    "section_title": item["topic"],
                    "excerpt": first_evidence.get("excerpt", ""),
                },
                "answer_evaluable": bool(answer_reference.get("answer_evaluable", True)),
                "reference_answer": reference_answer or first_evidence.get("excerpt", ""),
                "reference_points": [clean_text(point) for point in reference_points],
                "source": {
                    "dataset": "mteb/T2Retrieval",
                    "query_id": item["query_id"],
                    "qrel_count": len(item["expected_chunk_ids"]),
                    "review_evidence_chunk_id": evidence_id,
                    "original_question": item.get("source_question", item["question"]),
                },
                "status": item.get("status", "candidate"),
            }
        )
    return gold


def markdown(gold: list[dict[str, Any]]) -> str:
    approved = bool(gold) and all(case.get("status") == "approved" for case in gold)
    review_state = "已审核通过" if approved else "待人工审核"
    note = (
        "> 本文件已完成人工审核；对应 JSON 已获准作为正式 Benchmark Gold。"
        if approved
        else "> 本文件用于人工审核。`status=candidate` 的题目尚未替换线上 Gold。"
    )
    lines = [
        f"# T2Retrieval 专题 Gold v1（{review_state}）",
        "",
        note,
        "",
    ]
    current_topic = ""
    for case in gold:
        topic = case["scenario_tags"][1]
        if topic != current_topic:
            current_topic = topic
            lines.extend([f"## {topic}", ""])
        source = case["source"]
        lines.extend(
            [
                f"### {case['id']} · T2 query `{source['query_id']}`",
                "",
                f"- 问题：{case['question']}",
                f"- 正确 chunk 数：{source['qrel_count']}",
                f"- expected_chunk_ids：`{'`, `'.join(case['expected_chunk_ids'])}`",
                f"- 审核证据 chunk：`{source['review_evidence_chunk_id']}`",
                f"- 证据标题：{case['evidence']['title']}",
                f"- 证据摘录：{case['evidence']['excerpt']}",
                f"- 答案裁判：{'启用' if case['answer_evaluable'] else '跳过（证据不足或存在安全争议）'}",
                f"- 标准答案：{case['reference_answer']}",
                f"- 标准要点：{'；'.join(case['reference_points'])}",
                "",
            ]
        )
    return "\n".join(lines).rstrip() + "\n"


def main() -> int:
    args = parse_args()
    query_rows = read_parquet(args.queries).to_pylist()
    relevant: dict[str, list[str]] = defaultdict(list)
    for row in read_parquet(args.qrels).to_pylist():
        if int(row.get("score", 0)) > 0:
            relevant[str(row["query-id"])].append(str(row["corpus-id"]))
    relevant = {query_id: sorted(set(ids)) for query_id, ids in relevant.items()}
    selected = (
        select_manifest_cases(args.manifest, query_rows, relevant)
        if args.manifest
        else select_cases(query_rows, relevant, args.per_topic)
    )
    wanted_ids = {corpus_id for item in selected for corpus_id in item["expected_chunk_ids"]}
    evidence = load_evidence(args.corpus, wanted_ids)
    gold = build_gold(selected, evidence)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(gold, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    args.markdown_output.parent.mkdir(parents=True, exist_ok=True)
    args.markdown_output.write_text(markdown(gold), encoding="utf-8")
    topic_count = len({case["scenario_tags"][1] for case in gold})
    print(json.dumps({"cases": len(gold), "topics": topic_count, "output": str(args.output)}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
