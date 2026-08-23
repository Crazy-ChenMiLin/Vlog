"""Export public RAG source documents into a deterministic annotation snapshot.

The script is deliberately read-only: it queries public published post metadata,
downloads their existing content URLs, and reproduces the application's Markdown
chunk identities (``<postId>#<position>``).  Credentials are accepted only from
environment variables and are never included in output artifacts.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from pathlib import Path

import pymysql


@dataclass(frozen=True)
class Post:
    post_id: int
    title: str
    content_url: str


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Read public posts and export RAG chunks for annotation."
    )
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--summary", type=Path, required=True)
    parser.add_argument("--workers", type=int, default=12)
    parser.add_argument("--limit", type=int, default=0)
    return parser.parse_args()


def require_env(name: str) -> str:
    value = os.getenv(name, "").strip()
    if not value:
        raise RuntimeError(f"Missing required environment variable: {name}")
    return value


def load_posts(limit: int) -> list[Post]:
    connection = pymysql.connect(
        host=require_env("RAG_EVAL_MYSQL_HOST"),
        port=int(os.getenv("RAG_EVAL_MYSQL_PORT", "3306")),
        user=require_env("RAG_EVAL_MYSQL_USER"),
        password=require_env("RAG_EVAL_MYSQL_PASSWORD"),
        database=require_env("RAG_EVAL_MYSQL_DATABASE"),
        charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
        connect_timeout=10,
        read_timeout=30,
    )
    try:
        with connection.cursor() as cursor:
            sql = """
                SELECT id, title, content_url
                FROM know_posts
                WHERE status = 'published'
                  AND visible = 'public'
                  AND content_url IS NOT NULL
                  AND content_url <> ''
                ORDER BY id
            """
            if limit > 0:
                sql += " LIMIT %s"
                cursor.execute(sql, (limit,))
            else:
                cursor.execute(sql)
            rows = cursor.fetchall()
    finally:
        connection.close()
    return [
        Post(int(row["id"]), str(row.get("title") or ""), str(row["content_url"]))
        for row in rows
    ]


def fetch_markdown(post: Post) -> tuple[Post, str | None, str | None]:
    request = urllib.request.Request(post.content_url, method="GET")
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return post, response.read().decode("utf-8"), None
    except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, UnicodeDecodeError) as exc:
        return post, None, f"{type(exc).__name__}: {exc}"


def normalize_header(line: str) -> str:
    return line.lstrip("#").lstrip().strip()


def section_type(section_title: str) -> str:
    if not section_title:
        return "OTHER"
    if "核心概念" in section_title:
        return "CONCEPT"
    if "背景" in section_title:
        return "BACKGROUND"
    if "面试回答模板" in section_title:
        return "INTERVIEW_TEMPLATE"
    if "测试问题" in section_title:
        return "TEST_QUESTION"
    if "常见误区" in section_title or "坑" in section_title:
        return "PITFALL"
    if "解决" in section_title or "方案" in section_title or "排查" in section_title:
        return "SOLUTION"
    return "OTHER"


def split_sections(text: str) -> list[tuple[str, str]]:
    sections: list[tuple[str, str]] = []
    buffer: list[str] = []
    current_title = ""
    for line in text.splitlines():
        is_header = line.startswith("#")
        if is_header and buffer:
            sections.append(("\n".join(buffer) + "\n", current_title))
            buffer = []
        if is_header:
            current_title = normalize_header(line)
        buffer.append(line)
    if buffer:
        sections.append(("\n".join(buffer) + "\n", current_title))
    return sections


def chunk_markdown(text: str) -> list[tuple[str, str, str]]:
    """Mirror the current Java indexer's 800-char chunks with 100-char overlap."""
    chunks: list[tuple[str, str, str]] = []
    for section_text, title in split_sections(text):
        kind = section_type(title)
        if len(section_text) <= 800:
            chunks.append((section_text, title, kind))
            continue
        start = 0
        while start < len(section_text):
            end = min(start + 800, len(section_text))
            chunks.append((section_text[start:end], title, kind))
            if end >= len(section_text):
                break
            start = max(end - 100, start + 1)
    return chunks


def main() -> None:
    args = parse_args()
    posts = load_posts(args.limit)
    workers = max(1, min(args.workers, 32))
    successes: list[tuple[Post, str]] = []
    failures: list[dict[str, str | int]] = []

    with ThreadPoolExecutor(max_workers=workers) as executor:
        futures = {executor.submit(fetch_markdown, post): post for post in posts}
        for future in as_completed(futures):
            post, markdown, error = future.result()
            if markdown is None:
                failures.append({"post_id": post.post_id, "error": error or "unknown"})
            else:
                successes.append((post, markdown))

    successes.sort(key=lambda item: item[0].post_id)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    chunk_count = 0
    with args.output.open("w", encoding="utf-8", newline="\n") as stream:
        for post, markdown in successes:
            for position, (chunk, title, kind) in enumerate(chunk_markdown(markdown)):
                row = {
                    "post_id": str(post.post_id),
                    "chunk_id": f"{post.post_id}#{position}",
                    "title": post.title,
                    "section_title": title,
                    "section_type": kind,
                    "text": chunk,
                }
                stream.write(json.dumps(row, ensure_ascii=False) + "\n")
                chunk_count += 1

    summary = {
        "source": "published_public_know_posts",
        "posts_selected": len(posts),
        "posts_exported": len(successes),
        "chunks_exported": chunk_count,
        "read_failures": sorted(failures, key=lambda item: int(item["post_id"])),
        "output": str(args.output),
    }
    args.summary.parent.mkdir(parents=True, exist_ok=True)
    args.summary.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({
        "posts_selected": summary["posts_selected"],
        "posts_exported": summary["posts_exported"],
        "chunks_exported": summary["chunks_exported"],
        "read_failures": len(failures),
        "output": str(args.output),
    }, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"Corpus export failed: {type(error).__name__}: {error}", file=sys.stderr)
        raise SystemExit(1)
