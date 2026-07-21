"""对 graph-on / graph-off 两份 debug.json 做严格 A/B 对比（含 Top10 扩展）。

读取：
  --on    graph-on 的 debug.json（每题带 graphContext）
  --off   graph-off 的 debug.json（graphContext 为 null）
  --spec  relation-spec-100.json（关系题两端概念 spec）
  --out-dir  输出目录

产出 compare.json / compare.md：
  - graphContextHitCount（on / off）
  - Top5 / Top1 / Top10 双端概念覆盖率（on vs off）
  - rerank Top10 单块双端覆盖
  - graph-on 内部 Top5 vs Top10 增量（判断“召回到但排得靠后”）
  - 每题 rerankTop5 / rerankTop10 双端覆盖变化：变好 / 变差 / 不变
  - 重点题「缓存命中和缓存击穿是什么关系？」在 Top5 与 Top10 下是否翻盘
  - 若 JSON 无 Top10 字段且无完整 Results，明确报告需重跑评测
"""
import argparse
import json
from pathlib import Path

CONCEPT_ALIASES = {
    "命中": ["缓存命中", "命中率", "命中", "cache hit", "hit"],
    "击穿": ["缓存击穿", "击穿", "cache breakdown", "热点key失效", "热点 key 失效"],
    "穿透": ["缓存穿透", "穿透", "cache penetration", "缓存穿通"],
    "雪崩": ["缓存雪崩", "雪崩", "cache avalanche", "大量key同时过期", "大量 key 同时过期"],
    "布隆": ["布隆过滤器", "布隆", "bloomfilter", "bloom filter"],
    "空值": ["缓存空值", "空值", "空对象", "null caching", "缓存空值"],
    "互斥锁": ["互斥锁", "分布式锁", "mutex", "互斥重建", "singleflight"],
    "随机过期": ["随机过期时间", "随机过期", "过期抖动", "jitter"],
    "热点": ["热点key", "热点 key", "热点", "hotkey"],
}

EVIDENCE_WORDS = ["区别", "属于", "解决方案", "触发条件", "影响范围", "治理方式", "关系", "原因", "结果"]


def load_spec(path: Path) -> dict:
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def _txt(v) -> str:
    """把 chunk 字段统一成字符串（防御偶发的 list/None 类型）。"""
    if v is None:
        return ""
    if isinstance(v, list):
        return " ".join(str(x) for x in v)
    return str(v)


def concept_hit(concept_key: str, text: str) -> bool:
    text = (text or "").lower()
    return any(alias.lower() in text for alias in CONCEPT_ALIASES[concept_key])


def _get_topn(row: dict, base: str, n: int):
    """优先读 {base}Top{n}；否则从完整 {base}Results 截取前 n；都没有返回 None。

    base ∈ {"fused", "reranked"}。返回 None 表示当前 JSON 不足以计算该 Top-N。
    """
    direct = row.get(f"{base}Top{n}")
    if direct:
        return direct
    full = row.get(f"{base}Results")
    if isinstance(full, list):
        return full[:n]
    return None


def _topn_text(chunks) -> str:
    return " ".join(
        _txt(c.get("title")) + " " + _txt(c.get("sectionTitle")) + " " + _txt(c.get("textPreview"))
        for c in (chunks or [])
    )


def _both_in_single_chunk(chunks, concepts) -> bool:
    for c in (chunks or []):
        ct = _txt(c.get("title")) + " " + _txt(c.get("sectionTitle")) + " " + _txt(c.get("textPreview"))
        if all(concept_hit(cc, ct) for cc in concepts):
            return True
    return False


def _coverage(reranked_list, fused_list, concepts):
    """返回 (fused_both, rerank_both, top1_both, single_both, evidence)。"""
    reranked_text = _topn_text(reranked_list)
    fused_text = _topn_text(fused_list)
    top1 = reranked_list[0] if reranked_list else {}
    top1_text = _topn_text([top1]) if top1 else ""
    return (
        all(concept_hit(c, fused_text) for c in concepts),
        all(concept_hit(c, reranked_text) for c in concepts),
        all(concept_hit(c, top1_text) for c in concepts),
        _both_in_single_chunk(reranked_list, concepts),
        any(w in reranked_text for w in EVIDENCE_WORDS),
    )


def coverage_from_row(row: dict, spec: dict) -> dict:
    q = row.get("question", "")
    spec_row = spec.get(q)
    reranked5 = row.get("rerankedTop5") or []
    fused5 = row.get("fusedTop5") or []
    reranked10 = _get_topn(row, "reranked", 10)
    fused10 = _get_topn(row, "fused", 10)
    top10_available = reranked10 is not None and fused10 is not None

    result = {
        "question": q,
        "specMatched": bool(spec_row),
        "top10Available": top10_available,
        "fusedTop5ConceptCoverage": None,
        "rerankTop5ConceptCoverage": None,
        "rerankTop1ConceptCoverage": None,
        "rerankTop5SingleChunkCoverage": None,
        "fusedTop10ConceptCoverage": None,
        "rerankTop10ConceptCoverage": None,
        "rerankTop10SingleChunkCoverage": None,
        "relationEvidenceHit": None,
        "presentConcepts": [],
        "missingConcepts": [],
    }
    if not spec_row:
        return result

    concepts = spec_row.get("concepts", [])

    f5, r5, t1, s5, ev = _coverage(reranked5, fused5, concepts)
    result.update({
        "fusedTop5ConceptCoverage": f5,
        "rerankTop5ConceptCoverage": r5,
        "rerankTop1ConceptCoverage": t1,
        "rerankTop5SingleChunkCoverage": s5,
        "relationEvidenceHit": ev,
        "presentConcepts": [c for c in concepts if concept_hit(c, _topn_text(reranked5))],
        "missingConcepts": [c for c in concepts if not concept_hit(c, _topn_text(reranked5))],
    })
    if top10_available:
        f10, r10, _, s10, _ = _coverage(reranked10, fused10, concepts)
        result.update({
            "fusedTop10ConceptCoverage": f10,
            "rerankTop10ConceptCoverage": r10,
            "rerankTop10SingleChunkCoverage": s10,
        })
    return result


def aggregate(coverages: list[dict]) -> dict:
    matched = [c for c in coverages if c.get("specMatched")]
    n = len(matched)
    top10_available = any(c.get("top10Available") for c in matched)

    def rate(key):
        if n == 0:
            return None
        # Top10 指标在无 Top10 数据时显式返回 None（而非 0%），避免误导
        if "Top10" in key and not top10_available:
            return None
        return round(sum(1 for c in matched if c.get(key) is True) / n * 100, 1)

    return {
        "total": n,
        "fusedTop5ConceptCoverage": rate("fusedTop5ConceptCoverage"),
        "rerankTop5ConceptCoverage": rate("rerankTop5ConceptCoverage"),
        "rerankTop1ConceptCoverage": rate("rerankTop1ConceptCoverage"),
        "rerankTop5SingleChunkCoverage": rate("rerankTop5SingleChunkCoverage"),
        "fusedTop10ConceptCoverage": rate("fusedTop10ConceptCoverage"),
        "rerankTop10ConceptCoverage": rate("rerankTop10ConceptCoverage"),
        "rerankTop10SingleChunkCoverage": rate("rerankTop10SingleChunkCoverage"),
        "relationEvidenceHit": rate("relationEvidenceHit"),
        "top10Available": any(c.get("top10Available") for c in matched),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--on", type=Path, required=True)
    ap.add_argument("--off", type=Path, required=True)
    ap.add_argument("--spec", type=Path, required=True)
    ap.add_argument("--out-dir", type=Path, default=Path("target/rag-eval/graph-relation-300"))
    args = ap.parse_args()

    spec = load_spec(args.spec)
    on = json.loads(args.on.read_text(encoding="utf-8"))
    off = json.loads(args.off.read_text(encoding="utf-8"))

    on_cov = [coverage_from_row(r, spec) for r in on["rows"]]
    off_cov = [coverage_from_row(r, spec) for r in off["rows"]]

    on_agg = aggregate(on_cov)
    off_agg = aggregate(off_cov)

    # per-question change in rerankTop5 / rerankTop10 双端覆盖
    on_map = {c["question"]: c for c in on_cov if c.get("specMatched")}
    off_map = {c["question"]: c for c in off_cov if c.get("specMatched")}
    improved5, regressed5, unchanged5 = [], [], []
    improved10, regressed10, unchanged10 = [], [], []
    q1 = "缓存命中和缓存击穿是什么关系？"
    q1_result = {
        "onTop5": None, "offTop5": None, "flippedTop5": None,
        "onTop10": None, "offTop10": None, "flippedTop10": None,
    }
    for q in on_map:
        if q not in off_map:
            continue
        on5 = on_map[q]["rerankTop5ConceptCoverage"]
        off5 = off_map[q]["rerankTop5ConceptCoverage"]
        if on5 and not off5:
            improved5.append(q)
        elif off5 and not on5:
            regressed5.append(q)
        else:
            unchanged5.append(q)

        on10 = on_map[q].get("rerankTop10ConceptCoverage")
        off10 = off_map[q].get("rerankTop10ConceptCoverage")
        if on10 is None or off10 is None:
            unchanged10.append(q)
        elif on10 and not off10:
            improved10.append(q)
        elif off10 and not on10:
            regressed10.append(q)
        else:
            unchanged10.append(q)

        if q == q1:
            q1_result = {
                "onTop5": on5, "offTop5": off5, "flippedTop5": bool(on5 and not off5),
                "onTop10": on10, "offTop10": off10, "flippedTop10": bool(on10 and not off10),
            }

    on_gc_hit = on.get("graphContextHitCount")
    off_gc_hit = off.get("graphContextHitCount", 0)

    summary = {
        "testedAt": on.get("testedAt") or off.get("testedAt"),
        "questions": on.get("total"),
        "onErrors": on.get("errors"),
        "offErrors": off.get("errors"),
        "graphContextHitCount": {"on": on_gc_hit, "off": off_gc_hit},
        "onAggregate": on_agg,
        "offAggregate": off_agg,
        "improvedCountTop5": len(improved5),
        "regressedCountTop5": len(regressed5),
        "unchangedCountTop5": len(unchanged5),
        "improvedCountTop10": len(improved10),
        "regressedCountTop10": len(regressed10),
        "unchangedCountTop10": len(unchanged10),
        "q1CacheHitVsBreakdown": q1_result,
    }

    out_dir = args.out_dir
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "compare.json").write_text(
        json.dumps({**summary, "improvedTop5": improved5, "regressedTop5": regressed5, "unchangedTop5": unchanged5,
                    "improvedTop10": improved10, "regressedTop10": regressed10, "unchangedTop10": unchanged10,
                    "onPerQuestion": on_cov, "offPerQuestion": off_cov}, ensure_ascii=False, indent=2),
        encoding="utf-8")

    # ---- markdown ----
    lines = ["# Graph-On vs Graph-Off A/B 对比报告（关系型扩库 300 篇 · Top10 扩展）", ""]
    lines.append(f"- 测试时间：{summary['testedAt']}")
    lines.append(f"- 测试问题数：{summary['questions']}")
    lines.append(f"- graph-on 错误数：{summary['onErrors']} / graph-off 错误数：{summary['offErrors']}")
    lines.append(f"- graphContextHitCount：on={on_gc_hit} / off={off_gc_hit}")
    lines.append("")

    if not on_agg.get("top10Available"):
        lines.append("> ⚠️ **当前 JSON 不足以计算 Top10**：两文件均无 `fusedTop10`/`rerankedTop10` 字段，"
                     "也无完整的 `fusedResults`/`rerankedResults`。请修改评测脚本保存 Top10 快照后重跑评测。")
        lines.append("")

    lines.append("## 一、各核心命中率对比（on vs off）")
    lines.append("")
    lines.append("| 指标 | graph-off | graph-on | 变化 |")
    lines.append("| --- | --- | --- | --- |")
    metrics = [
        ("fusedTop5ConceptCoverage", "fused Top5 双端覆盖"),
        ("rerankTop5ConceptCoverage", "rerank Top5 双端覆盖"),
        ("rerankTop1ConceptCoverage", "rerank Top1 双端覆盖"),
        ("rerankTop5SingleChunkCoverage", "rerank Top5 单块双端覆盖"),
        ("fusedTop10ConceptCoverage", "fused Top10 双端覆盖"),
        ("rerankTop10ConceptCoverage", "rerank Top10 双端覆盖"),
        ("rerankTop10SingleChunkCoverage", "rerank Top10 单块双端覆盖"),
        ("relationEvidenceHit", "关系证据命中"),
    ]
    for key, label in metrics:
        o = off_agg.get(key)
        n = on_agg.get(key)
        if o is None or n is None:
            delta = "无数据"
        else:
            d = round(n - o, 1)
            delta = f"+{d}pp" if d >= 0 else f"{d}pp"
        o_s = f"{o}%" if o is not None else "—"
        n_s = f"{n}%" if n is not None else "—"
        lines.append(f"| {label} | {o_s} | {n_s} | {delta} |")
    lines.append("")

    # graph-on 内部 Top5 vs Top10 增量（核心判断）
    if on_agg.get("top10Available"):
        lines.append("## 二、graph-on 内部：Top5 vs Top10（判断「召回到但排得靠后」）")
        lines.append("")
        lines.append("| 指标 | Top5 | Top10 | 增量（Top10−Top5） |")
        lines.append("| --- | --- | --- | --- |")
        for top5k, top10k, label in [
            ("rerankTop5ConceptCoverage", "rerankTop10ConceptCoverage", "rerank 双端覆盖"),
            ("fusedTop5ConceptCoverage", "fusedTop10ConceptCoverage", "fused 双端覆盖"),
            ("rerankTop5SingleChunkCoverage", "rerankTop10SingleChunkCoverage", "rerank 单块双端覆盖"),
        ]:
            v5 = on_agg.get(top5k)
            v10 = on_agg.get(top10k)
            if v5 is None or v10 is None:
                inc = "—"
            else:
                d = round(v10 - v5, 1)
                inc = f"+{d}pp" if d >= 0 else f"{d}pp"
            lines.append(f"| {label} | {v5}% | {v10}% | {inc} |")
        lines.append("")
        lines.append("> 若 Top10 > Top5，说明相关内容被排在第 6–10 位（召回到但排得靠后）；"
                     "若两者持平，说明 graph 没有带来额外有效召回。")
        lines.append("")

    lines.append("## 三、每题双端覆盖变化")
    lines.append(f"- **rerank Top5**：变好 {len(improved5)} / 变差 {len(regressed5)} / 不变 {len(unchanged5)}")
    lines.append(f"- **rerank Top10**：变好 {len(improved10)} / 变差 {len(regressed10)} / 不变 {len(unchanged10)}")
    lines.append("")

    lines.append("## 四、重点题：「缓存命中和缓存击穿是什么关系？」")
    lines.append(f"- graph-off：Top5={q1_result['offTop5']} / graph-on：Top5={q1_result['onTop5']} / Top5 翻盘={q1_result['flippedTop5']}")
    lines.append(f"- graph-off：Top10={q1_result['offTop10']} / graph-on：Top10={q1_result['onTop10']} / Top10 翻盘={q1_result['flippedTop10']}")
    lines.append("")

    lines.append("## 五、rerank Top5 变好的题（节选前 20）")
    for q in improved5[:20]:
        lines.append(f"- {q}")
    lines.append("")
    if regressed5:
        lines.append("## 六、rerank Top5 仍变差 / 未提升的题（节选前 20）")
        for q in regressed5[:20]:
            lines.append(f"- {q}")
        lines.append("")

    (out_dir / "compare.md").write_text("\n".join(lines), encoding="utf-8")
    print(json.dumps({k: summary[k] for k in [
        "questions", "onErrors", "offErrors", "graphContextHitCount",
        "onAggregate", "offAggregate",
        "improvedCountTop5", "regressedCountTop5",
        "improvedCountTop10", "regressedCountTop10",
        "q1CacheHitVsBreakdown"]}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
