import argparse
import json
from collections import Counter
from pathlib import Path


BAD_SECTIONS = {"TEST_QUESTION", "INTERVIEW_TEMPLATE"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Compare bm25-off and bm25-on RAG debug evaluation outputs.")
    parser.add_argument("--off", type=Path, required=True)
    parser.add_argument("--on", type=Path, required=True)
    parser.add_argument("--output-json", type=Path, required=True)
    parser.add_argument("--output-md", type=Path, required=True)
    parser.add_argument("--title", default="RAG BM25 A/B 自动评估")
    return parser.parse_args()


def load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def chunk_key(chunk: dict | None) -> str | None:
    if not chunk:
        return None
    return chunk.get("chunkId") or f"{chunk.get('postId')}#{chunk.get('position')}"


def top(row: dict, field: str) -> dict | None:
    values = row.get(field) or []
    return values[0] if values else None


def section(chunk: dict | None) -> str:
    return (chunk or {}).get("sectionType") or "NONE"


def preview(chunk: dict | None, limit: int = 80) -> str:
    text = ((chunk or {}).get("textPreview") or "").replace("\n", " ")
    return text[:limit]


def judge(off_chunk: dict | None, on_chunk: dict | None) -> str:
    if chunk_key(off_chunk) == chunk_key(on_chunk):
        return "不变"
    off_bad = section(off_chunk) in BAD_SECTIONS
    on_bad = section(on_chunk) in BAD_SECTIONS
    if off_bad and not on_bad:
        return "变好"
    if not off_bad and on_bad:
        return "变差"
    if section(off_chunk) == "BACKGROUND" and section(on_chunk) == "CONCEPT":
        return "变好"
    if section(off_chunk) == "CONCEPT" and section(on_chunk) == "BACKGROUND":
        return "变差"
    return "需人工复核"


def count_section(rows: list[dict], field: str) -> Counter:
    return Counter(section(top(row, field)) for row in rows)


def bad_count(rows: list[dict], field: str) -> int:
    return sum(1 for row in rows if section(top(row, field)) in BAD_SECTIONS)


def compare(off: dict, on: dict) -> dict:
    rows = []
    for off_row, on_row in zip(off.get("rows") or [], on.get("rows") or []):
        off_fused = top(off_row, "fusedTop5")
        on_fused = top(on_row, "fusedTop5")
        off_rerank = top(off_row, "rerankedTop5")
        on_rerank = top(on_row, "rerankedTop5")
        rows.append({
            "question": off_row.get("question"),
            "offFusedTop1Key": chunk_key(off_fused),
            "onFusedTop1Key": chunk_key(on_fused),
            "offFusedTop1Section": section(off_fused),
            "onFusedTop1Section": section(on_fused),
            "offRerankTop1Key": chunk_key(off_rerank),
            "onRerankTop1Key": chunk_key(on_rerank),
            "offRerankTop1Section": section(off_rerank),
            "onRerankTop1Section": section(on_rerank),
            "keywordOnlyCandidateCount": on_row.get("keywordOnlyCandidateCount", 0),
            "keywordOnlyInFusedCount": on_row.get("keywordOnlyInFusedCount", 0),
            "keywordOnlyInRerankedTop5Count": on_row.get("keywordOnlyInRerankedTop5Count", 0),
            "verdict": judge(off_rerank, on_rerank),
            "offRerankTitle": (off_rerank or {}).get("title"),
            "onRerankTitle": (on_rerank or {}).get("title"),
            "offRerankPreview": preview(off_rerank),
            "onRerankPreview": preview(on_rerank),
        })

    verdicts = Counter(row["verdict"] for row in rows)
    summary = {
        "offTestedAt": off.get("testedAt"),
        "onTestedAt": on.get("testedAt"),
        "total": min(off.get("total", 0), on.get("total", 0)),
        "offErrors": off.get("errors", 0),
        "onErrors": on.get("errors", 0),
        "offKeywordReturnedCount": off.get("keywordReturnedCount", 0),
        "onKeywordReturnedCount": on.get("keywordReturnedCount", 0),
        "onKeywordOnlyCandidateTotal": on.get("keywordOnlyCandidateTotal", 0),
        "onKeywordCandidatesInFusedTotal": on.get("keywordCandidatesInFusedTotal", 0),
        "onKeywordOnlyInFusedTotal": on.get("keywordOnlyInFusedTotal", 0),
        "onKeywordOnlyInRerankedTop5Total": on.get("keywordOnlyInRerankedTop5Total", 0),
        "onKeywordOnlyInRerankedTop5QuestionCount": on.get("keywordOnlyInRerankedTop5QuestionCount", 0),
        "onFusedTop1FromKeywordCount": on.get("fusedTop1FromKeywordCount", 0),
        "onRerankTop1FromKeywordCount": on.get("rerankTop1FromKeywordCount", 0),
        "fusedTop1ChangedCount": sum(
            1 for row in rows if row["offFusedTop1Key"] != row["onFusedTop1Key"]
        ),
        "rerankTop1ChangedCount": sum(
            1 for row in rows if row["offRerankTop1Key"] != row["onRerankTop1Key"]
        ),
        "offFusedBadSectionCount": bad_count(off.get("rows") or [], "fusedTop5"),
        "onFusedBadSectionCount": bad_count(on.get("rows") or [], "fusedTop5"),
        "offRerankBadSectionCount": bad_count(off.get("rows") or [], "rerankedTop5"),
        "onRerankBadSectionCount": bad_count(on.get("rows") or [], "rerankedTop5"),
        "improvedCount": verdicts["变好"],
        "worseCount": verdicts["变差"],
        "unchangedCount": verdicts["不变"],
        "needsReviewCount": verdicts["需人工复核"],
        "offFusedSectionDistribution": dict(count_section(off.get("rows") or [], "fusedTop5")),
        "onFusedSectionDistribution": dict(count_section(on.get("rows") or [], "fusedTop5")),
        "offRerankSectionDistribution": dict(count_section(off.get("rows") or [], "rerankedTop5")),
        "onRerankSectionDistribution": dict(count_section(on.get("rows") or [], "rerankedTop5")),
    }
    total = summary["total"] or 1
    summary["improveRate"] = round(summary["improvedCount"] / total, 4)
    summary["worseRate"] = round(summary["worseCount"] / total, 4)
    summary["netImproveRate"] = round((summary["improvedCount"] - summary["worseCount"]) / total, 4)
    return {"summary": summary, "rows": rows}


def pct(value: float) -> str:
    return f"{value * 100:.1f}%"


def distribution_table(title: str, off_dist: dict, on_dist: dict) -> list[str]:
    keys = sorted(set(off_dist) | set(on_dist))
    lines = [f"{title}：", "", "| sectionType | bm25-off | bm25-on | 变化 |", "|---|---:|---:|---:|"]
    for key in keys:
        off_value = off_dist.get(key, 0)
        on_value = on_dist.get(key, 0)
        lines.append(f"| {key} | {off_value} | {on_value} | {on_value - off_value:+d} |")
    lines.append("")
    return lines


def render_markdown(result: dict, title: str) -> str:
    summary = result["summary"]
    rows = result["rows"]
    lines = [
        f"## {title}",
        "",
        "测试时间：",
        "",
        "```text",
        f"bm25-off：{summary['offTestedAt']}",
        f"bm25-on ：{summary['onTestedAt']}",
        "```",
        "",
        "本轮核心结论：",
        "",
        "```text",
        f"测试问题数：{summary['total']}",
        f"fusedTop1 变化：{summary['fusedTop1ChangedCount']} / {summary['total']}",
        f"rerankTop1 变化：{summary['rerankTop1ChangedCount']} / {summary['total']}",
        f"变好：{summary['improvedCount']}，变差：{summary['worseCount']}，不变：{summary['unchangedCount']}，需人工复核：{summary['needsReviewCount']}",
        f"净优化率：{pct(summary['netImproveRate'])}",
        "```",
        "",
        "本轮指标：",
        "",
        "| 指标 | bm25-off | bm25-on |",
        "|---|---:|---:|",
        f"| 接口错误数 | {summary['offErrors']} | {summary['onErrors']} |",
        f"| BM25 有返回的问题数 | {summary['offKeywordReturnedCount']} | {summary['onKeywordReturnedCount']} |",
        f"| BM25 独有候选总数 | 0 | {summary['onKeywordOnlyCandidateTotal']} |",
        f"| BM25 候选进入 fused 总数 | 0 | {summary['onKeywordCandidatesInFusedTotal']} |",
        f"| BM25 独有候选进入 fused 总数 | 0 | {summary['onKeywordOnlyInFusedTotal']} |",
        f"| BM25 独有候选进入 rerank Top5 总数 | 0 | {summary['onKeywordOnlyInRerankedTop5Total']} |",
        f"| BM25 独有候选进入 rerank Top5 的问题数 | 0 | {summary['onKeywordOnlyInRerankedTop5QuestionCount']} |",
        f"| fusedTop1 变化题数 | - | {summary['fusedTop1ChangedCount']} |",
        f"| rerankTop1 变化题数 | - | {summary['rerankTop1ChangedCount']} |",
        "",
    ]
    lines.extend(distribution_table(
        "fusedTop1 章节分布",
        summary["offFusedSectionDistribution"],
        summary["onFusedSectionDistribution"],
    ))
    lines.extend(distribution_table(
        "rerankTop1 章节分布",
        summary["offRerankSectionDistribution"],
        summary["onRerankSectionDistribution"],
    ))
    lines.extend([
        "bad section 观察：",
        "",
        "| 指标 | bm25-off | bm25-on |",
        "|---|---:|---:|",
        f"| fusedTop1 落到 TEST_QUESTION / INTERVIEW_TEMPLATE | {summary['offFusedBadSectionCount']} / {summary['total']} | {summary['onFusedBadSectionCount']} / {summary['total']} |",
        f"| rerankTop1 落到 TEST_QUESTION / INTERVIEW_TEMPLATE | {summary['offRerankBadSectionCount']} / {summary['total']} | {summary['onRerankBadSectionCount']} / {summary['total']} |",
        "",
        "本轮优化率：",
        "",
        "| 指标 | 结果 | 计算方式 |",
        "|---|---:|---|",
        f"| 变好数 | {summary['improvedCount']} | rerankTop1 更符合当前问题意图的数量 |",
        f"| 变差数 | {summary['worseCount']} | rerankTop1 更偏离当前问题意图的数量 |",
        f"| 不变数 | {summary['unchangedCount']} | rerankTop1 chunk 完全相同 |",
        f"| 需人工复核 | {summary['needsReviewCount']} | chunk 变化但启发式无法判断 |",
        f"| 优化率 | {pct(summary['improveRate'])} | 变好数 / {summary['total']} |",
        f"| 劣化率 | {pct(summary['worseRate'])} | 变差数 / {summary['total']} |",
        f"| 净优化率 | {pct(summary['netImproveRate'])} | (变好数 - 变差数) / {summary['total']} |",
        "",
        "逐题对比：",
        "",
        "| # | 问题 | fusedTop1 off -> on | rerankTop1 off -> on | BM25 独有进 fused | BM25 独有进 rerank Top5 | 判断 |",
        "|---:|---|---|---|---:|---:|---|",
    ])
    for index, row in enumerate(rows, 1):
        lines.append(
            f"| {index} | {row['question']} | "
            f"{row['offFusedTop1Section']} -> {row['onFusedTop1Section']} | "
            f"{row['offRerankTop1Section']} -> {row['onRerankTop1Section']} | "
            f"{row['keywordOnlyInFusedCount']} | "
            f"{row['keywordOnlyInRerankedTop5Count']} | "
            f"{row['verdict']} |"
        )
    lines.append("")
    return "\n".join(lines)


def main() -> None:
    args = parse_args()
    result = compare(load(args.off), load(args.on))
    args.output_json.parent.mkdir(parents=True, exist_ok=True)
    args.output_md.parent.mkdir(parents=True, exist_ok=True)
    args.output_json.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    args.output_md.write_text(render_markdown(result, args.title), encoding="utf-8")
    print(json.dumps(result["summary"], ensure_ascii=False, indent=2))
    print(str(args.output_json))
    print(str(args.output_md))


if __name__ == "__main__":
    main()
