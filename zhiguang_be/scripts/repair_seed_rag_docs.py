import hashlib
import json
import os
import re
import time
import uuid
from pathlib import Path
from urllib.parse import urljoin

import jwt
import pymysql
import requests
import yaml


BASE = os.getenv("RAG_SEED_BASE", "http://localhost:8080")
USER_ID = 1


FAMILIES = [
    ("Redis", ["缓存", "分布式", "高并发"], ["缓存穿透", "缓存击穿", "缓存雪崩", "过期策略", "淘汰策略", "布隆过滤器", "分布式锁", "热点 key", "缓存一致性", "延迟双删"]),
    ("Spring Boot", ["Spring", "后端", "Java"], ["自动配置", "Bean 生命周期", "事务传播", "AOP", "过滤器链", "异常处理", "参数校验", "配置绑定", "Actuator", "WebClient"]),
    ("MySQL", ["MySQL", "数据库", "索引"], ["B+Tree 索引", "事务隔离级别", "MVCC", "慢查询优化", "联合索引", "覆盖索引", "锁等待", "分页优化", "执行计划", "主从复制"]),
    ("Elasticsearch", ["ES", "搜索", "向量检索"], ["倒排索引", "分词器", "BM25", "向量相似度", "mapping", "refresh", "bulk 写入", "聚合查询", "过滤器", "召回评估"]),
    ("Kafka", ["Kafka", "消息队列", "异步"], ["消费者组", "分区顺序", "幂等生产者", "事务消息", "重试队列", "死信队列", "offset 管理", "再均衡", "削峰填谷", "Exactly Once"]),
    ("RAG", ["RAG", "LLM", "检索增强"], ["切片策略", "HyDE", "RRF", "查询改写", "多轮记忆", "rerank", "召回评估", "上下文压缩", "向量库", "引用溯源"]),
    ("Java Stream", ["Java", "Stream", "集合"], ["map", "flatMap", "filter", "collect", "reduce", "groupingBy", "并行流", "Optional", "Comparator", "peek 调试"]),
    ("系统设计", ["架构", "设计", "高可用"], ["限流", "熔断", "降级", "幂等", "分库分表", "读写分离", "接口防刷", "任务调度", "灰度发布", "可观测性"]),
    ("安全认证", ["安全", "JWT", "认证"], ["JWT", "刷新令牌", "RBAC", "CSRF", "XSS", "密码哈希", "接口签名", "OAuth2", "登录态", "权限校验"]),
    ("前端工程", ["前端", "React", "工程化"], ["组件拆分", "状态管理", "请求封装", "SSE 流式输出", "路由守卫", "表单校验", "构建优化", "错误边界", "虚拟列表", "响应式布局"]),
]


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def load_config():
    config = yaml.safe_load(read_text(Path("src/main/resources/application.yml")))
    spring = config["spring"]
    datasource = spring["datasource"]
    vector_es = spring["ai"]["vectorstore"]["elasticsearch"]
    elasticsearch = spring["elasticsearch"]
    mysql_url = datasource["url"]
    mysql_user = datasource["username"]
    mysql_password = datasource["password"]
    es_index = vector_es["index-name"]
    es_url = elasticsearch["uris"]
    es_user = elasticsearch.get("username", "elastic")
    es_password = elasticsearch["password"]

    match = re.search(r"jdbc:mysql://([^:/]+):(\d+)/([^?]+)", mysql_url)
    if not match:
        raise RuntimeError(f"unsupported mysql url: {mysql_url}")

    return {
        "mysql_host": match.group(1),
        "mysql_port": int(match.group(2)),
        "mysql_db": match.group(3),
        "mysql_user": mysql_user,
        "mysql_password": mysql_password,
        "es_url": es_url.rstrip("/"),
        "es_user": es_user,
        "es_index": es_index,
        "es_password": es_password,
    }


def article(index: int):
    family, base_tags, topics = FAMILIES[index % len(FAMILIES)]
    topic = topics[(index // len(FAMILIES)) % len(topics)]
    n = index + 1
    title = f"RAG 知识库扩展 {n:03d}：{family} - {topic}"
    description = f"{family} {topic} 核心概念与排查要点"
    tags = list(dict.fromkeys([family, topic] + base_tags))[:5]
    content = f"""# {title}

## 背景

这篇文章用于扩展知光 RAG 知识库，帮助测试全库问答、HyDE、RRF、多轮记忆和后续 rerank 的真实效果。主题是 **{family}：{topic}**。

## 核心概念

{topic} 是 {family} 学习中的高频问题。理解它时不要只背定义，更要关注它解决了什么问题、适合放在哪个链路、失败时会暴露什么现象。

## 常见判断

1. 如果问题发生在读多写少、高并发或异步链路里，先判断瓶颈是不是数据访问、网络等待或外部服务不稳定。
2. 如果系统表现为偶发慢、偶发错，要结合日志、指标和请求链路定位，而不是只看单个接口返回。
3. 如果用户问题带有“为什么”“怎么优化”“和什么区别”，RAG 检索应该召回定义、场景、优缺点和排查步骤。

## 排查步骤

- 先确认现象：错误码、耗时、影响范围、是否可复现。
- 再确认边界：是单个用户、单篇文章、全库问答，还是所有请求都异常。
- 然后看数据：数据库记录、对象存储内容、ES 切片数量和检索结果。
- 最后做修复：优先选择影响面小、可回滚、可验证的方案。

## 面试回答模板

回答 {family} 的 {topic} 时，可以按“定义 -> 场景 -> 风险 -> 方案 -> 观测指标”的顺序组织。这样既能讲清楚原理，也能体现工程落地能力。

## RAG 测试问题

- {topic} 是什么？
- {family} 里 {topic} 常见问题怎么排查？
- {topic} 和相关方案有什么区别？
- 如果线上出现 {topic} 相关故障，应该先看哪些指标？
"""
    return title, description, tags, content


def jwt_headers():
    private_key = read_text(Path("src/main/resources/keys/private.pem"))
    now = int(time.time())
    token = jwt.encode(
        {
            "sub": str(USER_ID),
            "uid": USER_ID,
            "typ": "access",
            "iat": now,
            "exp": now + 3600,
            "jti": str(uuid.uuid4()),
        },
        private_key,
        algorithm="RS256",
    )
    return {"Authorization": f"Bearer {token}"}


def api(method: str, path: str, headers: dict, **kwargs):
    response = requests.request(method, urljoin(BASE, path), headers=headers, timeout=60, **kwargs)
    if response.status_code >= 400:
        raise RuntimeError(f"{method} {path} -> {response.status_code}: {response.text[:500]}")
    return response


def find_seed_posts(config: dict):
    conn = pymysql.connect(
        host=config["mysql_host"],
        port=config["mysql_port"],
        user=config["mysql_user"],
        password=config["mysql_password"],
        database=config["mysql_db"],
        charset="utf8mb4",
    )
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT id, title
                FROM know_posts
                WHERE creator_id = %s
                  AND status = 'published'
                  AND visible = 'public'
                  AND (title LIKE 'RAG ?????%%' OR title LIKE 'RAG 知识库扩展 %%')
                ORDER BY create_time ASC, id ASC
                """,
                (USER_ID,),
            )
            return [str(row[0]) for row in cur.fetchall()]
    finally:
        conn.close()


def delete_es_chunks(config: dict, post_id: str):
    response = requests.post(
        f"{config['es_url']}/{config['es_index']}/_delete_by_query?conflicts=proceed&refresh=true",
        auth=(config["es_user"], config["es_password"]),
        headers={"Content-Type": "application/json"},
        data=json.dumps({"query": {"term": {"metadata.postId": post_id}}}, ensure_ascii=False).encode("utf-8"),
        timeout=60,
    )
    if response.status_code >= 400:
        raise RuntimeError(f"ES delete failed for {post_id}: {response.status_code} {response.text[:500]}")


def repair_post(post_id: str, index: int, headers: dict, config: dict):
    title, description, tags, content = article(index)
    body = content.encode("utf-8")
    sha256 = hashlib.sha256(body).hexdigest()

    presign_response = api(
        "POST",
        "/api/v1/storage/presign",
        headers,
        json={
            "postId": post_id,
            "scene": "knowpost_content",
            "contentType": "text/markdown",
            "ext": ".md",
        },
    ).json()
    presign = presign_response.get("data", presign_response)

    put_response = requests.put(
        presign["putUrl"],
        data=body,
        headers={"Content-Type": "text/markdown"},
        timeout=60,
    )
    if put_response.status_code >= 400:
        raise RuntimeError(f"PUT object failed for {post_id}: {put_response.status_code} {put_response.text[:300]}")

    etag = put_response.headers.get("ETag", "").strip('"')
    api(
        "POST",
        f"/api/v1/knowposts/{post_id}/content/confirm",
        headers,
        json={
            "objectKey": presign["objectKey"],
            "etag": etag,
            "size": len(body),
            "sha256": sha256,
        },
    )
    api(
        "PATCH",
        f"/api/v1/knowposts/{post_id}",
        headers,
        json={
            "title": title,
            "tags": tags,
            "visible": "public",
            "isTop": False,
            "description": description,
        },
    )

    delete_es_chunks(config, post_id)
    chunks = api("POST", f"/api/v1/knowposts/{post_id}/rag/reindex", headers).text.strip()
    return title, chunks


def main():
    config = load_config()
    headers = jwt_headers()
    ids = find_seed_posts(config)
    if len(ids) != 100:
        raise RuntimeError(f"expected 100 generated posts, found {len(ids)}")

    repaired = []
    for index, post_id in enumerate(ids):
        title, chunks = repair_post(post_id, index, headers, config)
        repaired.append((post_id, title, chunks))
        print(f"fixed {index + 1:03d}/100 {post_id} chunks={chunks} {title}")

    print(json.dumps({"repaired": len(repaired), "base": BASE}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
