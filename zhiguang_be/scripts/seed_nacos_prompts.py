#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把知光项目 8 个硬编码 RAG prompt 一次性灌进 Nacos AI Prompt 管理。
流程：登录 -> POST(创建+提交) -> force-publish(发布) -> online(上线)。
"""
import json
import urllib.request
import urllib.parse
import urllib.error

BASE = "http://100.83.242.114:8848/nacos"
VERSION = "1.0.0"
COMMIT = "init: migrate from hardcoded Java prompt"


def http_post(path, params):
    params = dict(params)
    data = urllib.parse.urlencode(params).encode("utf-8")
    req = urllib.request.Request(BASE + path, data=data,
                                 headers={"Content-Type": "application/x-www-form-urlencoded"})
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            body = resp.read().decode("utf-8", "replace")
            return resp.status, body
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")[:300]
    except Exception as e:
        return -1, str(e)[:300]


def login():
    status, body = http_post("/v1/auth/login",
                             {"username": "nacos", "password": "nacos"})
    if status == 200:
        return json.loads(body)["accessToken"]
    raise RuntimeError("login failed: %s %s" % (status, body))


PROMPTS = [
    {
        "key": "rag-planner-system",
        "desc": "RAG 主 Agent 规划器：判断问题类型与检索策略，返回 JSON",
        "tags": "rag,agent,planner",
        "template": """You are the planner of a Chinese RAG main agent.
Return JSON only. Do not answer the user's question.
Available tools:
- direct_answer: answer small talk without retrieval.
- keyword_search: exact keyword/BM25/Elasticsearch search.
- vector_search: semantic vector search.
- hyde: generate a hypothetical answer for semantic retrieval.
- graph_trace: query Neo4j graph trace for relation/comparison questions.
- rerank: rerank retrieved chunks before answering.
Schema:
{
  "questionType": "CHAT|KEYWORD_LOOKUP|NORMAL_QA|RELATION_QA",
  "retrievalMode": "NONE|KEYWORD_ONLY|HYBRID|GRAPH_AUGMENTED_HYBRID",
  "needDirectAnswer": true,
  "needKeywordSearch": false,
  "needVectorSearch": false,
  "needHyde": false,
  "needGraphTrace": false,
  "needRerank": false,
  "initialTopK": 5,
  "reason": "short Chinese reason"
}
Prefer RELATION_QA and graph_trace for comparison, relation, cause, influence, difference questions.
Prefer KEYWORD_LOOKUP for very short technical terms.
Prefer NORMAL_QA for definitions, why/how/principle questions.""",
    },
    {
        "key": "rag-evidence-system",
        "desc": "证据检查：判断检索到的证据是否足够回答，返回 JSON",
        "tags": "rag,agent,evidence",
        "template": """You judge whether retrieved RAG evidence is enough to answer a Chinese user question.
Return JSON only. Do not answer the question.
Schema:
{
  "sufficient": true,
  "score": 0.0,
  "reason": "short Chinese reason",
  "suggestedAction": "NONE|EXPAND_TOP_K|ANSWER_WITH_LIMITATION"
}
Use EXPAND_TOP_K only if more candidates may help. Use ANSWER_WITH_LIMITATION if evidence is still weak but retry is already used.""",
    },
    {
        "key": "rag-final-answer-system",
        "desc": "主回答（无历史）：依据知识库上下文和 graph trace 回答",
        "tags": "rag,answer",
        "template": "你是中文知识助手。只能依据提供的知识库上下文和 Neo4j graph trace 回答；无法确定时请说明不确定。",
    },
    {
        "key": "rag-final-answer-with-history-system",
        "desc": "主回答（带历史）：对话历史仅用于理解，答案必须基于上下文",
        "tags": "rag,answer,history",
        "template": """你是中文知识助手。对话历史只用于理解用户当前问题，改写问题只表示系统对当前问题的理解。
最终答案必须基于提供的知识库上下文和 Neo4j graph trace；无法确定时请说明不确定。""",
    },
    {
        "key": "rag-rewrite-system",
        "desc": "查询改写：结合历史把问题改写为独立检索问题",
        "tags": "rag,rewrite",
        "template": """你是 RAG 查询改写器。根据最近对话历史，把用户当前问题改写成一个独立、完整、适合向量检索的中文问题。
只输出改写后的问题，不要回答问题，不要解释，不要添加编号。
如果当前问题已经完整，原样或轻微补全后输出。""",
    },
    {
        "key": "rag-hyde-system",
        "desc": "HyDE：生成假设性答案用于语义检索",
        "tags": "rag,enhance,hyde",
        "template": "你是知识库检索查询转换器。根据用户问题生成一段可能出现在知识库正文中的中文答案，用于语义检索。只输出2到3句陈述性正文，不要解释任务，不要添加标题、引用或来源，不要向用户提问。",
    },
    {
        "key": "rag-graph-understanding-system",
        "desc": "图查询理解：抽取实体与关系意图供 Neo4j 检索，返回 JSON",
        "tags": "rag,graph",
        "template": """You extract graph retrieval signals from a Chinese technical RAG question.
Return JSON only. Do not answer the question.
Schema:
{
  "entities": ["canonical technical concept names"],
  "relationIntent": "COMPARE|CAUSE|PART_OF|SOLUTION|RELATED|UNKNOWN",
  "questionType": "RELATION|CONCEPT|SOLUTION|TEST|UNKNOWN"
}
Use canonical Chinese concept names when possible, for example:
缓存命中, 缓存击穿, 缓存穿透, 缓存雪崩, 布隆过滤器, 分布式锁, Redis.""",
    },
    {
        "key": "rag-direct-answer-system",
        "desc": "闲聊直答：不需要检索时直接简洁回答",
        "tags": "rag,answer,chat",
        "template": "你是中文助手。用户的问题不需要知识库检索时，直接简洁回答；如果涉及实时信息，请说明无法确认实时状态。",
    },
]


def main():
    token = login()
    print("登录成功，token 长度 %d" % len(token))
    print("=" * 70)
    ok = 0
    for p in PROMPTS:
        key = p["key"]
        base = {"namespaceId": "", "accessToken": token}

        # 1. 创建 + 提交
        s1, b1 = http_post("/v3/admin/ai/prompt", {
            **base, "promptKey": key, "version": VERSION,
            "template": p["template"], "commitMsg": COMMIT,
            "description": p["desc"], "bizTags": p["tags"],
        })
        # 2. 发布
        s2, b2 = http_post("/v3/admin/ai/prompt/force-publish", {
            **base, "promptKey": key, "version": VERSION,
        })
        # 3. 上线
        s3, b3 = http_post("/v3/admin/ai/prompt/online", {
            **base, "promptKey": key, "version": VERSION,
        })

        if s1 == 200 and s2 == 200 and s3 == 200:
            ok += 1
            print("[OK] %s  (create=%s publish=%s online=%s)" % (key, s1, s2, s3))
        else:
            print("[FAIL] %s  create=%s publish=%s online=%s" % (key, s1, s2, s3))
            if s1 != 200:
                print("   create body: %s" % b1)
            if s2 != 200:
                print("   publish body: %s" % b2)
            if s3 != 200:
                print("   online body: %s" % b3)

    print("=" * 70)
    print("完成：%d/%d 成功" % (ok, len(PROMPTS)))


if __name__ == "__main__":
    main()
