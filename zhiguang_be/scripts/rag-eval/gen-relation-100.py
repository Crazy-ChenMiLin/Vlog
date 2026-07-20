"""
生成「缓存关系型」100 题测试集 + 对应的关系 spec。

设计：
- 9 个核心概念（命中/击穿/穿透/雪崩/布隆/空值/互斥锁/热点/随机过期），按角色（state/problem/mechanism/load）组合。
- 关系类型（区别/关系/影响范围/触发条件/因果/治理方式/解决方案）按角色对自动决定适用性。
- 产出：
    questions-relation-cache-100.json  -> 纯字符串数组（兼容 run-rag-debug-eval.py）
    relation-spec-100.json             -> {question: {concepts, relation, focus?, threeWay?}}
  分析脚本 analyze-relation-hit.py 通过 --spec 加载后者，无需在脚本里硬编码 100 条。
"""
import itertools
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent

# 概念 key -> (问题中的展示名, 角色)
CONCEPTS = {
    "命中": ("缓存命中", "state"),
    "击穿": ("缓存击穿", "problem"),
    "穿透": ("缓存穿透", "problem"),
    "雪崩": ("缓存雪崩", "problem"),
    "布隆": ("布隆过滤器", "mechanism"),
    "空值": ("缓存空值", "mechanism"),
    "互斥锁": ("互斥锁", "mechanism"),
    "随机过期": ("随机过期时间", "mechanism"),
    "热点": ("热点 key", "load"),
}

# problem key -> 能解决它的 mechanism key 集合
SOLUTION = {
    "穿透": {"布隆", "空值"},
    "击穿": {"互斥锁"},
    "雪崩": {"随机过期"},
}


def make_question(rel: str, la: str, lb: str) -> str | None:
    if rel == "关系":
        return f"{la}和{lb}是什么关系？"
    if rel == "区别":
        return f"{la}和{lb}有什么区别？"
    if rel == "影响范围":
        return f"{la}和{lb}的影响范围有什么不同？"
    if rel == "触发条件":
        return f"{la}和{lb}的触发条件分别是什么？"
    if rel == "因果":
        return f"{la}和{lb}之间有什么因果关系？"
    if rel == "治理方式":
        return f"{la}和{lb}的治理方式有什么不同？"
    if rel == "解决方案":
        return f"{la}和{lb}是什么关系？为什么说{lb}能解决{la}？"
    return None


def allowed_relations(ra: str, rb: str):
    # 用 frozenset 做顺序无关匹配，避免 sorted 后 key 顺序不一致导致漏配
    s = frozenset({ra, rb})
    if s == {"problem", "problem"}:
        return ["区别", "关系", "影响范围", "触发条件", "因果", "治理方式"]
    if s == {"problem", "mechanism"}:
        return "PROBLEM_MECH"  # 按方向单独处理
    if s == {"problem", "state"}:
        return ["关系", "因果", "区别"]
    if s == {"problem", "load"}:
        return ["因果", "关系", "区别", "治理方式"]
    if s == {"mechanism", "mechanism"}:
        return ["区别", "关系", "治理方式"]
    if s == {"mechanism", "state"}:
        return ["关系"]
    if s == {"mechanism", "load"}:
        return ["关系", "治理方式"]
    if s == {"state", "load"}:
        return ["关系"]
    return []


def main() -> None:
    items = []  # (question, spec)
    seen = set()

    # 重点关注题（命中 vs 击穿）——用户预判它会低
    focus_q = "缓存命中和缓存击穿是什么关系？"
    items.append((focus_q, {
        "concepts": ["命中", "击穿"],
        "relation": "关系/因果（命中是击穿的反面前提）",
        "focus": True,
    }))
    seen.add(focus_q)

    for a, b in itertools.combinations(CONCEPTS.keys(), 2):
        la, ra = CONCEPTS[a]
        lb, rb = CONCEPTS[b]
        rels = allowed_relations(ra, rb)
        if rels == "PROBLEM_MECH":
            prob, mech = (a, b) if ra == "problem" else (b, a)
            if mech in SOLUTION.get(prob, set()):
                for rel in ["解决方案", "关系", "区别"]:
                    q = make_question(rel, CONCEPTS[prob][0], CONCEPTS[mech][0])
                    if q and q not in seen:
                        seen.add(q)
                        rel_label = rel if rel != "解决方案" else f"解决方案（{CONCEPTS[mech][0]}解决{CONCEPTS[prob][0]}）"
                        items.append((q, {"concepts": [prob, mech], "relation": rel_label}))
            else:
                for rel in ["关系", "区别"]:
                    q = make_question(rel, la, lb)
                    if q and q not in seen:
                        seen.add(q)
                        items.append((q, {"concepts": [a, b], "relation": rel}))
            continue
        for rel in rels:
            q = make_question(rel, la, lb)
            if q and q not in seen:
                seen.add(q)
                items.append((q, {"concepts": [a, b], "relation": rel}))

    # 三者关系（穿透/击穿/雪崩）
    three_way = [
        ("Redis 缓存三大问题里，缓存穿透、缓存击穿、缓存雪崩三者是什么关系？",
         ["穿透", "击穿", "雪崩"], "三者关系/区别"),
        ("缓存穿透、缓存击穿、缓存雪崩的影响范围各有什么不同？",
         ["穿透", "击穿", "雪崩"], "区别/影响范围"),
        ("缓存穿透、缓存击穿、缓存雪崩的触发条件分别是什么？",
         ["穿透", "击穿", "雪崩"], "触发条件"),
    ]
    for q, cs, rel in three_way:
        if q not in seen:
            seen.add(q)
            items.append((q, {"concepts": cs, "relation": rel, "threeWay": True}))

    # 取前 100（focus + 全部 98 道两两关系题 + 1 道三者题）
    items = items[:100]
    assert len(items) == 100, f"预期 100 题，实际 {len(items)}"

    questions = [q for q, _ in items]
    spec = {q: s for q, s in items}

    (HERE / "questions-relation-cache-100.json").write_text(
        json.dumps(questions, ensure_ascii=False, indent=2), encoding="utf-8")
    (HERE / "relation-spec-100.json").write_text(
        json.dumps(spec, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"生成题目数：{len(questions)}")
    print(f"关系 spec 条数：{len(spec)}")
    # 统计关系类型分布
    from collections import Counter
    rc = Counter(s.get("relation", "").split("（")[0] for s in spec.values())
    print("关系类型分布：", dict(rc))


if __name__ == "__main__":
    main()
