import json

import pymysql
import requests

import repair_seed_rag_docs as seed


def main():
    config = seed.load_config()
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
            cur.execute("SELECT COUNT(*) FROM know_posts WHERE status = 'published' AND visible = 'public'")
            print("public_published", cur.fetchone()[0])

            cur.execute("SELECT COUNT(*) FROM know_posts WHERE title LIKE 'RAG 知识库扩展 %'")
            print("generated", cur.fetchone()[0])

            cur.execute("SELECT COUNT(*) FROM know_posts WHERE title LIKE 'RAG ?????%'")
            print("mojibake_titles", cur.fetchone()[0])

            cur.execute(
                """
                SELECT id, title, content_url
                FROM know_posts
                WHERE title LIKE 'RAG 知识库扩展 %'
                ORDER BY create_time ASC
                LIMIT 1
                """
            )
            post_id, title, content_url = cur.fetchone()
            text = requests.get(content_url, timeout=20).content.decode("utf-8")
            print("sample_db", post_id, title)
            print("sample_content", text[:160].replace("\n", " | "))

            cur.execute(
                """
                SELECT id
                FROM know_posts
                WHERE title LIKE 'RAG 知识库扩展 %'
                ORDER BY create_time ASC
                """
            )
            ids = [str(row[0]) for row in cur.fetchall()]
    finally:
        conn.close()

    query = {
        "query": {"terms": {"metadata.postId": ids}},
        "aggs": {"by_post": {"terms": {"field": "metadata.postId.keyword", "size": 200}}},
        "size": 0,
    }
    response = requests.post(
        f"{config['es_url']}/{config['es_index']}/_search",
        auth=(config["es_user"], config["es_password"]),
        json=query,
        timeout=30,
    )
    response.raise_for_status()
    buckets = response.json()["aggregations"]["by_post"]["buckets"]
    print("es_posts_with_chunks", len(buckets))
    print("es_total_chunks", sum(bucket["doc_count"] for bucket in buckets))
    print("es_min_chunks", min(bucket["doc_count"] for bucket in buckets))
    print("es_max_chunks", max(bucket["doc_count"] for bucket in buckets))
    missing = sorted(set(ids) - {bucket["key"] for bucket in buckets})
    print("es_missing_posts", json.dumps(missing, ensure_ascii=False))


if __name__ == "__main__":
    main()
