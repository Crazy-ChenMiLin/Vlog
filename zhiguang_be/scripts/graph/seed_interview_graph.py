import argparse
import json
import os
from dataclasses import dataclass

from neo4j import GraphDatabase


@dataclass(frozen=True)
class Concept:
    name: str
    category: str
    aliases: tuple[str, ...] = ()


CONCEPTS = [
    Concept("Redis 缓存问题", "topic", ("Redis 缓存", "缓存问题")),
    Concept("缓存命中", "concept", ("命中率", "命中", "cache hit")),
    Concept("缓存穿透", "concept", ("穿透", "cache penetration")),
    Concept("缓存击穿", "concept", ("击穿", "热点 key 失效", "cache breakdown")),
    Concept("缓存雪崩", "concept", ("雪崩", "大量 key 同时过期", "cache avalanche")),
    Concept("布隆过滤器", "solution", ("Bloom Filter",)),
    Concept("缓存空值", "solution", ("空值缓存",)),
    Concept("互斥锁", "solution", ("mutex", "分布式锁")),
    Concept("随机过期时间", "solution", ("过期时间抖动", "TTL jitter")),
    Concept("JWT", "topic", ("Json Web Token",)),
    Concept("Access Token", "concept", ("access token", "访问令牌")),
    Concept("Refresh Token", "concept", ("refresh token", "刷新令牌")),
    Concept("Redis", "middleware", ()),
    Concept("RedisRefreshTokenStore", "service", ()),
    Concept("JwtService", "service", ()),
]

RELATIONS = [
    ("缓存穿透", "PART_OF", "Redis 缓存问题", "缓存穿透属于 Redis 缓存异常类面试问题"),
    ("缓存命中", "PART_OF", "Redis 缓存问题", "缓存命中是 Redis 缓存正常工作时的状态"),
    ("缓存击穿", "PART_OF", "Redis 缓存问题", "缓存击穿属于 Redis 缓存异常类面试问题"),
    ("缓存雪崩", "PART_OF", "Redis 缓存问题", "缓存雪崩属于 Redis 缓存异常类面试问题"),
    ("缓存命中", "OPPOSITE_OR_PRECONDITION", "缓存击穿", "缓存命中是请求直接从缓存返回；缓存击穿发生在热点 key 失效、请求绕过缓存集中打到数据库，二者是缓存是否生效的正反两面"),
    ("缓存命中", "COMPARE_WITH", "缓存雪崩", "缓存命中是正常命中；缓存雪崩是大量 key 同时失效导致命中率骤降"),
    ("缓存命中", "RELATED_TO", "热点 key", "热点 key 是否命中直接决定缓存击穿是否发生"),
    ("缓存穿透", "COMPARE_WITH", "缓存击穿", "二者都属于 Redis 缓存异常，但原因和治理方式不同"),
    ("缓存穿透", "COMPARE_WITH", "缓存雪崩", "二者都属于 Redis 缓存异常，但影响范围不同"),
    ("缓存击穿", "COMPARE_WITH", "缓存雪崩", "二者都可能造成数据库压力，但触发条件不同"),
    ("缓存穿透", "MITIGATED_BY", "布隆过滤器", "布隆过滤器可拦截不存在的数据请求"),
    ("缓存穿透", "MITIGATED_BY", "缓存空值", "缓存空值可避免不存在数据反复打到数据库"),
    ("缓存击穿", "MITIGATED_BY", "互斥锁", "热点 key 失效时可用互斥锁限制回源并发"),
    ("缓存雪崩", "MITIGATED_BY", "随机过期时间", "随机过期时间可减少大量 key 同时过期"),
    ("随机过期时间", "RELATED_TO", "Redis 缓存问题", "随机过期时间是 Redis 缓存雪崩的常用治理手段"),
    ("随机过期时间", "RELATED_TO", "缓存击穿", "为热点 key 设置随机过期时间可降低击穿概率"),
    ("随机过期时间", "RELATED_TO", "热点 key", "热点 key 搭配随机过期时间可避免集中失效"),
    ("互斥锁", "RELATED_TO", "Redis 缓存问题", "互斥锁是 Redis 缓存击穿的常用治理手段"),
    ("互斥锁", "RELATED_TO", "缓存雪崩", "缓存雪崩时互斥锁可限制回源并发"),
    ("互斥锁", "RELATED_TO", "热点 key", "热点 key 失效时用互斥锁限制回源并发"),
    ("Access Token", "PART_OF", "JWT", "Access Token 是 JWT 认证体系的一部分"),
    ("Refresh Token", "PART_OF", "JWT", "Refresh Token 是 JWT 认证体系的一部分"),
    ("Access Token", "COMPARE_WITH", "Refresh Token", "二者职责不同：访问令牌短期使用，刷新令牌用于续期"),
    ("RedisRefreshTokenStore", "STORES", "Refresh Token", "知光项目通过 RedisRefreshTokenStore 管理刷新令牌状态"),
    ("Redis", "STORES", "Refresh Token", "知光项目 Redis 主要保存刷新令牌相关状态"),
    ("JwtService", "ISSUES", "Access Token", "JwtService 签发访问令牌"),
    ("JwtService", "ISSUES", "Refresh Token", "JwtService 签发刷新令牌"),
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Seed and verify lightweight interview knowledge graph.")
    parser.add_argument("--uri", default=os.environ.get("NEO4J_URI", "bolt://100.83.242.114:7687"))
    parser.add_argument("--username", default=os.environ.get("NEO4J_USERNAME", "neo4j"))
    parser.add_argument("--password", default=os.environ.get("NEO4J_PASSWORD"))
    parser.add_argument("--verify-query", default="Redis 缓存穿透、击穿、雪崩有什么区别？")
    return parser.parse_args()


def seed_graph(driver) -> None:
    with driver.session() as session:
        session.execute_write(create_constraints)
        for concept in CONCEPTS:
            session.execute_write(upsert_concept, concept)
        for start, relation_type, end, description in RELATIONS:
            session.execute_write(upsert_relation, start, relation_type, end, description)


def create_constraints(tx) -> None:
    tx.run("CREATE CONSTRAINT concept_name_unique IF NOT EXISTS FOR (c:Concept) REQUIRE c.name IS UNIQUE")


def upsert_concept(tx, concept: Concept) -> None:
    tx.run(
        """
        MERGE (c:Concept {name: $name})
        SET c.category = $category,
            c.aliases = $aliases
        """,
        name=concept.name,
        category=concept.category,
        aliases=list(concept.aliases),
    )


def upsert_relation(tx, start: str, relation_type: str, end: str, description: str) -> None:
    tx.run(
        """
        MATCH (a:Concept {name: $start})
        MATCH (b:Concept {name: $end})
        MERGE (a)-[r:RELATED {type: $type}]->(b)
        SET r.description = $description
        """,
        start=start,
        end=end,
        type=relation_type,
        description=description,
    )


def extract_entities(question: str) -> list[str]:
    normalized = question.lower()
    matched = []
    for concept in CONCEPTS:
        names = (concept.name, *concept.aliases)
        if any(name.lower() in normalized for name in names):
            matched.append(concept.name)
    return matched


def verify_context(driver, entities: list[str]) -> dict:
    if not entities:
        return {"matchedEntities": [], "relations": [], "parentConcepts": [], "expandedTerms": []}

    with driver.session() as session:
        records = session.execute_read(find_relations, entities)

    relations = []
    parent_concepts = set()
    expanded_terms = set(entities)
    for record in records:
        relation = {
            "from": record["from"],
            "type": record["type"],
            "to": record["to"],
            "description": record["description"],
        }
        relations.append(relation)
        expanded_terms.add(record["from"])
        expanded_terms.add(record["to"])
        if record["type"] == "PART_OF":
            parent_concepts.add(record["to"])

    return {
        "matchedEntities": entities,
        "relations": relations,
        "parentConcepts": sorted(parent_concepts),
        "expandedTerms": sorted(expanded_terms),
    }


def find_relations(tx, entities: list[str]) -> list[dict]:
    result = tx.run(
        """
        MATCH (a:Concept)-[r:RELATED]->(b:Concept)
        WHERE a.name IN $entities OR b.name IN $entities
        RETURN a.name AS from, r.type AS type, b.name AS to, r.description AS description
        ORDER BY from, type, to
        """,
        entities=entities,
    )
    return [record.data() for record in result]


def main() -> None:
    args = parse_args()
    if not args.password:
        raise SystemExit("NEO4J_PASSWORD is required")

    driver = GraphDatabase.driver(args.uri, auth=(args.username, args.password))
    try:
        driver.verify_connectivity()
        seed_graph(driver)
        entities = extract_entities(args.verify_query)
        context = verify_context(driver, entities)
        print(json.dumps(context, ensure_ascii=False, indent=2))
    finally:
        driver.close()


if __name__ == "__main__":
    main()
