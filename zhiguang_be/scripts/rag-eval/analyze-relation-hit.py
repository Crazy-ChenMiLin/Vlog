"""
关系型 RAG 命中分析：基于 run-rag-debug-eval.py 产出的 debug.json，
计算“关系两端是否同时召回 + 是否有关系证据”的指标。

这不是另起框架，而是现有 eval 链路的关系指标层：
  run-rag-debug-eval.py  -> debug.json（原始召回）
  analyze-relation-hit.py -> relation-hit-summary.json / .md（关系指标）

关键原则（见任务要求）：
  - 不把“BM25 有返回”误判为关系命中。
  - 真正要看的是：关系两端概念是否同时进入 Top5，以及是否有证据能解释两端关系。
  - 概念检测只用强短语（如“缓存命中”“缓存击穿”），避免把“命中率”等弱信号误算。
"""
import argparse
import json
import re
from pathlib import Path


def parse_args() -> argparse.Namespace:
    here = Path(__file__).resolve().parent
    default_in = here.parents[1] / "target" / "rag-eval" / "relation-cache-baseline" / "debug.json"
    default_out = here.parents[1] / "target" / "rag-eval" / "relation-cache-baseline"
    parser = argparse.ArgumentParser(description="Analyze relation-type RAG hit from debug.json.")
    parser.add_argument("--input", type=Path, default=default_in)
    parser.add_argument("--output-dir", type=Path, default=default_out)
    parser.add_argument("--spec", type=Path, default=None,
                        help="关系 spec JSON（{question: {concepts, relation, focus?, threeWay?}}）。"
                             "不传则使用脚本内置的 10 题基线 spec。")
    return parser.parse_args()


# ---- 概念强短语匹配（避免弱信号误判）----
CONCEPT_PATTERNS = {
    "命中": ["缓存命中", "命中缓存"],
    "击穿": ["缓存击穿", "击穿"],
    "穿透": ["缓存穿透", "穿透"],
    "雪崩": ["缓存雪崩", "雪崩"],
    "布隆": ["布隆过滤器", "布隆"],
    "空值": ["缓存空值", "空值", "空对象"],
    "互斥锁": ["互斥锁", "互斥", "mutex"],
    "热点": ["热点", "hot key", "热点key", "热点 key", "热点数据", "hotkey"],
}


def has_random_expiry(text: str) -> bool:
    t = text.lower()
    if "随机" in text and ("过期" in text or "ttl" in t or "expire" in t):
        return True
    if "错开" in text:
        return True
    if "过期" in text and ("错峰" in text or "分散" in text or "随机" in text or "不同" in text):
        return True
    if "ttl" in t and ("分散" in text or "错开" in text or "随机" in text):
        return True
    return False


CONCEPT_FUNCS = {
    "随机过期": has_random_expiry,
}


def concept_present(concept: str, text: str) -> bool:
    if concept in CONCEPT_FUNCS:
        return CONCEPT_FUNCS[concept](text)
    for pat in CONCEPT_PATTERNS.get(concept, []):
        if pat.lower() in text.lower():
            return True
    return False


# ---- 关系证据（能解释“两端关系”的文本信号）----
EVIDENCE_PATTERNS = {
    "区别": ["区别", "差异", "不同", "对比", "辨析", "区分", "区别对待"],
    "属于": ["属于", "是一种", "是一类", "归类", "范畴", "类别", "包含于", "从属"],
    "解决方案": ["解决方案", "解决办法", "如何应对", "怎么解决", "如何避免", "如何防止", "处理方法", "缓解", "对策", "手段"],
    "触发条件": ["触发", "导致", "原因", "诱因", "何时", "什么情况下", "前提", "条件"],
    "影响范围": ["影响范围", "影响", "后果", "危害", "影响面", "冲击"],
    "治理方式": ["治理", "优化", "最佳实践", "防护", "防范", "规避", "架构设计", "设计思路"],
}


def evidence_hit(text: str) -> list[str]:
    hits = []
    for label, pats in EVIDENCE_PATTERNS.items():
        if any(p in text for p in pats):
            hits.append(label)
    return hits


# ---- 每道题的关系两端定义（按顺序：概念列表 + 关系类型）----
RELATION_SPECS = {
    "缓存命中和缓存击穿是什么关系？": {"concepts": ["命中", "击穿"], "relation": "关系/因果（命中是击穿的反面前提）", "focus": True},
    "缓存穿透和缓存击穿有什么区别？": {"concepts": ["穿透", "击穿"], "relation": "区别"},
    "缓存击穿和缓存雪崩有什么区别？": {"concepts": ["击穿", "雪崩"], "relation": "区别"},
    "缓存穿透和缓存雪崩的影响范围有什么不同？": {"concepts": ["穿透", "雪崩"], "relation": "区别/影响范围"},
    "缓存击穿和热点 key 失效是什么关系？": {"concepts": ["击穿", "热点"], "relation": "因果/触发（热点key失效触发击穿）"},
    "缓存穿透和布隆过滤器是什么关系？": {"concepts": ["穿透", "布隆"], "relation": "解决方案（布隆过滤器解决穿透）"},
    "缓存穿透和缓存空值是什么关系？": {"concepts": ["穿透", "空值"], "relation": "解决方案（缓存空值解决穿透）"},
    "缓存击穿和互斥锁是什么关系？": {"concepts": ["击穿", "互斥锁"], "relation": "解决方案（互斥锁解决击穿）"},
    "缓存雪崩和随机过期时间是什么关系？": {"concepts": ["雪崩", "随机过期"], "relation": "解决方案/触发（随机过期防止雪崩）"},
    "Redis 缓存问题里，缓存穿透、缓存击穿、缓存雪崩三者是什么关系？": {
        "concepts": ["穿透", "击穿", "雪崩"], "relation": "三者关系/区别", "threeWay": True,
    },
}


def chunk_text(chunk: dict) -> str:
    return " ".join([
        chunk.get("title") or "",
        chunk.get("sectionTitle") or "",
        chunk.get("textPreview") or "",
    ])


def concept_coverage(concepts: list[str], chunks: list[dict]) -> dict:
    """返回每个概念在 chunks 中是否出现（跨 chunk，不要求同块）。"""
    per = {}
    for c in concepts:
        per[c] = any(concept_present(c, chunk_text(ch)) for ch in chunks)
    return per


def single_chunk_covers_all(concepts: list[str], chunks: list[dict]) -> bool:
    for ch in chunks:
        txt = chunk_text(ch)
        if all(concept_present(c, txt) for c in concepts):
            return True
    return False


def analyze_row(row: dict) -> dict:
    q = row.get("question", "")
    spec = RELATION_SPECS.get(q)
    if not spec:
        return {"question": q, "specMatched": False}

    concepts = spec["concepts"]
    fused = row.get("fusedTop5") or []
    rerank = row.get("rerankedTop5") or []

    fused_cov = concept_coverage(concepts, fused)
    rerank_cov = concept_coverage(concepts, rerank)

    fused_top5_cover = all(fused_cov.values())
    rerank_top5_cover = all(rerank_cov.values())

    top1 = rerank[0] if rerank else None
    rerank_top1_cover = (
        all(concept_present(c, chunk_text(top1)) for c in concepts)
        if top1 else False
    )
    rerank_top5_single = single_chunk_covers_all(concepts, rerank)

    # 关系证据：在 rerank Top5 任意 chunk 文本中命中
    ev = set()
    for ch in rerank:
        ev.update(evidence_hit(chunk_text(ch)))
    relation_evidence_hit = len(ev) > 0

    # 概念召回（用于报告“只命中单概念”）
    present_concepts = [c for c in concepts if rerank_cov.get(c)]
    missing_concepts = [c for c in concepts if not rerank_cov.get(c)]

    # Top1 / Top5 概览
    def compact(ch):
        if not ch:
            return None
        return {
            "rank": ch.get("rank"),
            "postId": ch.get("postId"),
            "chunkId": ch.get("chunkId"),
            "title": ch.get("title"),
            "sectionTitle": ch.get("sectionTitle"),
            "rerankScore": ch.get("rerankScore"),
            "textPreview": (ch.get("textPreview") or "")[:120],
        }

    return {
        "question": q,
        "specMatched": True,
        "relation": spec.get("relation"),
        "focus": bool(spec.get("focus")),
        "threeWay": bool(spec.get("threeWay")),
        "concepts": concepts,
        "fusedTop5ConceptCoverage": fused_top5_cover,
        "rerankTop5ConceptCoverage": rerank_top5_cover,
        "rerankTop1ConceptCoverage": rerank_top1_cover,
        "rerankTop5SingleChunkCoverage": rerank_top5_single,
        "relationEvidenceHit": relation_evidence_hit,
        "evidenceTypes": sorted(ev),
        "presentConcepts": present_concepts,
        "missingConcepts": missing_concepts,
        "fusedConceptPresent": fused_cov,
        "rerankConceptPresent": rerank_cov,
        "top1": compact(top1),
        "top5": [compact(ch) for ch in rerank[:5]],
    }


def main() -> None:
    global RELATION_SPECS
    args = parse_args()
    if args.spec and args.spec.exists():
        loaded = json.loads(args.spec.read_text(encoding="utf-8"))
        RELATION_SPECS = loaded
        print(f"[spec] 已从 {args.spec} 加载 {len(RELATION_SPECS)} 条关系规格")
    if not args.input.exists():
        raise SystemExit(f"找不到 debug.json：{args.input}\n请先运行 run-rag-debug-eval.py 生成基线 debug.json。")

    debug = json.loads(args.input.read_text(encoding="utf-8"))
    rows = debug.get("rows") or []
    errors = debug.get("errorsDetail") or []

    analyses = [analyze_row(r) for r in rows]

    def rate(key: str) -> float:
        matched = [a for a in analyses if a.get("specMatched")]
        if not matched:
            return 0.0
        return round(sum(1 for a in matched if a.get(key)) / len(matched) * 100, 1)

    # “只命中单概念、没命中关系”的题：rerank 两端覆盖为 False，但至少命中了一个概念
    single_only = [
        a for a in analyses
        if a.get("specMatched") and not a.get("rerankTop5ConceptCoverage")
        and len(a.get("presentConcepts") or []) >= 1
    ]

    summary = {
        "label": debug.get("label"),
        "testedAt": debug.get("testedAt"),
        "baseUrl": debug.get("baseUrl"),
        "endpoint": debug.get("endpoint"),
        "questionsFile": debug.get("questionsFile"),
        "specFile": str(args.spec) if args.spec else "builtin(10题基线)",
        "total": debug.get("total"),
        "errors": debug.get("errors"),
        "specMatched": sum(1 for a in analyses if a.get("specMatched")),
        "metrics": {
            "fusedTop5ConceptCoverage": rate("fusedTop5ConceptCoverage"),
            "rerankTop5ConceptCoverage": rate("rerankTop5ConceptCoverage"),
            "rerankTop1ConceptCoverage": rate("rerankTop1ConceptCoverage"),
            "rerankTop5SingleChunkCoverage": rate("rerankTop5SingleChunkCoverage"),
            "relationEvidenceHit": rate("relationEvidenceHit"),
        },
        "singleConceptOnlyQuestions": [a["question"] for a in single_only],
        "focusQuestion": next((a for a in analyses if a.get("focus")), None),
        "perQuestion": analyses,
    }

    args.output_dir.mkdir(parents=True, exist_ok=True)
    out_json = args.output_dir / "relation-hit-summary.json"
    out_md = args.output_dir / "relation-hit-summary.md"
    out_json.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    out_md.write_text(render_md(summary, analyses), encoding="utf-8")

    print(json.dumps(summary["metrics"], ensure_ascii=False, indent=2))
    print(f"JSON: {out_json}")
    print(f"MD:   {out_md}")


def render_md(summary: dict, analyses: list[dict]) -> str:
    m = summary["metrics"]
    total = summary.get("total") or len([a for a in analyses if a.get("specMatched")])
    lines = []
    label = summary.get("label") or "relation-cache"
    lines.append(f"# 关系型 RAG 命中报告（{label}）")
    lines.append("")
    lines.append(f"- **测试时间**：{summary.get('testedAt')}")
    lines.append(f"- **后端地址/接口**：{summary.get('baseUrl')}  {summary.get('endpoint')}")
    lines.append(f"- **测试集**：{summary.get('questionsFile')}")
    lines.append(f"- **测试问题数**：{summary.get('total')}（已匹配关系规格 {summary.get('specMatched')} 题）")
    lines.append(f"- **错误数**：{summary.get('errors')}")
    lines.append("")
    lines.append("## 一、各项命中率（关系两端同时召回 + 关系证据）")
    lines.append("")
    lines.append("| 指标 | 命中率 | 说明 |")
    lines.append("| --- | --- | --- |")
    lines.append(f"| fusedTop5ConceptCoverage | **{m['fusedTop5ConceptCoverage']}%** | 关系两端概念是否都进入 fused Top5 |")
    lines.append(f"| rerankTop5ConceptCoverage | **{m['rerankTop5ConceptCoverage']}%** | 关系两端概念是否都进入 rerank Top5 |")
    lines.append(f"| rerankTop1ConceptCoverage | **{m['rerankTop1ConceptCoverage']}%** | rerank Top1 单个 chunk 是否同时覆盖两端 |")
    lines.append(f"| rerankTop5SingleChunkCoverage | **{m['rerankTop5SingleChunkCoverage']}%** | rerank Top5 中是否存在单 chunk 同时说明两端关系 |")
    lines.append(f"| relationEvidenceHit | **{m['relationEvidenceHit']}%** | rerank Top5 是否出现区别/属于/解决方案/触发/影响/治理等证据 |")
    lines.append("")
    lines.append("> 注意：以上指标不把“BM25 有返回”当成关系命中。真正的关系命中要求**两端概念同时召回**，且**有文本证据能解释两端关系**。")
    lines.append("")
    lines.append("## 二、逐题 Top1 / Top5 情况")
    lines.append("")
    for a in analyses:
        if not a.get("specMatched"):
            lines.append(f"### ⚠️ 未匹配关系规格：{a.get('question')}")
            lines.append("")
            continue
        flag = " 🎯重点关注" if a.get("focus") else ""
        lines.append(f"### {a['question']}{flag}")
        lines.append(f"- 关系类型：{a.get('relation')}")
        lines.append(
            f"- 指标：fusedTop5双端=`{a['fusedTop5ConceptCoverage']}` | "
            f"rerankTop5双端=`{a['rerankTop5ConceptCoverage']}` | "
            f"rerankTop1双端=`{a['rerankTop1ConceptCoverage']}` | "
            f"单块双端=`{a['rerankTop5SingleChunkCoverage']}` | "
            f"关系证据=`{a['relationEvidenceHit']}`"
        )
        lines.append(f"- 命中概念：{a.get('presentConcepts')}　缺失概念：{a.get('missingConcepts') or '无'}")
        if a.get("evidenceTypes"):
            lines.append(f"- 关系证据类型：{a.get('evidenceTypes')}")
        t1 = a.get("top1")
        if t1:
            lines.append(
                f"- **Top1**：postId={t1.get('postId')} chunkId={t1.get('chunkId')} "
                f"rerankScore={t1.get('rerankScore')} 标题={t1.get('title')} / {t1.get('sectionTitle')}"
            )
            lines.append(f"  - 预览：{t1.get('textPreview')}")
        lines.append(f"- **Top5 概览**：")
        for ch in a.get("top5") or []:
            lines.append(
                f"  - #{ch.get('rank')} postId={ch.get('postId')} chunkId={ch.get('chunkId')} "
                f"score={ch.get('rerankScore')} 标题={ch.get('title')} / {ch.get('sectionTitle')}"
            )
        lines.append("")
    lines.append("## 三、只命中单概念、未命中关系的题目")
    lines.append("")
    if summary.get("singleConceptOnlyQuestions"):
        for q in summary["singleConceptOnlyQuestions"]:
            lines.append(f"- {q}")
    else:
        lines.append("- 无（所有题两端概念均同时召回，或完全未召回）。")
    lines.append("")
    lines.append("## 四、重点关注题：缓存命中 vs 缓存击穿")
    lines.append("")
    fq = summary.get("focusQuestion")
    if fq:
        lines.append(f"- fusedTop5双端覆盖：`{fq['fusedTop5ConceptCoverage']}`")
        lines.append(f"- rerankTop5双端覆盖：`{fq['rerankTop5ConceptCoverage']}`")
        lines.append(f"- rerankTop1双端覆盖：`{fq['rerankTop1ConceptCoverage']}`")
        lines.append(f"- rerankTop5单块双端：`{fq['rerankTop5SingleChunkCoverage']}`")
        lines.append(f"- 关系证据命中：`{fq['relationEvidenceHit']}`")
        lines.append(f"- 命中概念：`{fq.get('presentConcepts')}`　缺失概念：`{fq.get('missingConcepts')}`")
        lines.append("")
        lines.append("> 预期：该题很可能低。当前文档/图关系里未必有“缓存命中”节点或关系文本，")
        lines.append("> 检索通常只召回“缓存击穿”侧，导致关系两端无法同时覆盖。")
    lines.append("")
    lines.append("## 五、结论：关系型问题的弱点（数据驱动）")
    lines.append("")
    matched = [a for a in analyses if a.get("specMatched")]
    failed = [a for a in matched if not a.get("rerankTop5ConceptCoverage")]
    lines.append(f"- **双端覆盖失败题数**：{len(failed)} / {len(matched)}（rerankTop5 未同时召回关系两端概念）。")
    if failed:
        lines.append("- **失败题清单（关系两端未同时召回，按缺失概念分组）**：")
        for a in failed:
            lines.append(f"  - {a['question']}　缺失概念：`{a.get('missingConcepts')}`")
    else:
        lines.append("- 所有关系题两端概念均同时召回（双端覆盖 100%）。")
    # 瓶颈判断：单块双端率 vs 双端覆盖率
    if m["rerankTop5SingleChunkCoverage"] == m["rerankTop5ConceptCoverage"]:
        lines.append(f"- **瓶颈在「节点覆盖度」而非 rerank/融合**：单块双端覆盖率（{m['rerankTop5SingleChunkCoverage']}%）"
                     f"与双端覆盖率（{m['rerankTop5ConceptCoverage']}%）持平，")
        lines.append("  说明一旦两端概念都有索引节点，融合与重排就能把它们聚到一起；断链只发生在某端概念在语料中无独立节点时。")
    else:
        lines.append(f"- **存在「单块解释两端」缺口**：双端覆盖率 {m['rerankTop5ConceptCoverage']}%，"
                     f"但单块双端仅 {m['rerankTop5SingleChunkCoverage']}%，")
        lines.append("  说明两端虽都被召回，却常分散在不同 chunk，缺乏“一段把两者关系讲清”的文本。")
    lines.append("- **关系证据词易 incidental 命中**：即便关系未双端覆盖，rerankTop5 仍可能命中「区别/解决方案」等证据词"
                 "（单侧文本顺带匹配），不能算关系命中；务必以「双端覆盖」为主指标。")
    fq = summary.get("focusQuestion")
    if fq:
        lines.append(f"- **重点关注题「{fq['question']}」**：rerankTop5双端=`{fq['rerankTop5ConceptCoverage']}`、"
                     f"缺失概念=`{fq.get('missingConcepts') or '无'}`。")
        lines.append("  预期：若语料缺「缓存命中」节点，该题会低——这是当前检索对「命中」侧召回不足的典型表现。")
    lines.append("- **改进方向（供下一轮对比）**：")
    lines.append("  1) 在知识图谱/索引中补缺失的关系节点与关系文本（如「缓存命中」「热点 key」）；")
    lines.append("  2) 对关系型问题做查询改写（query rewriting），把“A和B的关系”拆成 A、B 子查询分别召回再合并；")
    lines.append("  3) 在检索阶段引入关系感知的 rerank 特征（两端共现、关系证据词权重）。")
    lines.append("")
    lines.append("## 六、方法说明（指标透明度）")
    lines.append("")
    lines.append("- **概念检测字段**：每個 chunk 用 `title + sectionTitle + textPreview`（textPreview 为原文前 240 字）拼接后做子串匹配。")
    lines.append("- **强短语匹配**：概念只用强短语（如「缓存命中」「缓存击穿」「缓存穿透」），避免把「命中率」等弱信号误算为概念命中。")
    lines.append("- **BM25 有返回 ≠ 关系命中**：本报告所有指标要求「关系两端概念同时召回 + 有证据文本」，")
    lines.append(f"  不把 keywordResults 非空（BM25 有返回）当作关系命中。本次 {total} 题 keyword 可能全部非空，"
                 "但只有双端覆盖的题才算真正的关系命中。")
    lines.append("")
    return "\n".join(lines)


if __name__ == "__main__":
    main()
