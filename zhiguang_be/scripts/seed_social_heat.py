"""Seed realistic social heat for demo community data.

This script updates Redis counter SDS values for public published KnowPosts.
It is intended for demo/staging data only: MySQL content remains unchanged,
while like/favorite counters and user aggregate counters become more realistic.

Run from zhiguang_be:
  python scripts/seed_social_heat.py

Optional env:
  SOCIAL_HEAT_LIMIT=1000
  SOCIAL_HEAT_DRY_RUN=1
  SOCIAL_HEAT_BITMAPS=1
"""

from __future__ import annotations

import hashlib
import os
import re
import socket
import struct
from collections import defaultdict
from pathlib import Path

import pymysql
import yaml


ROOT = Path(__file__).resolve().parents[1]
SCHEMA_ID = "v1"
ENTITY_TYPE = "knowpost"
SDS_LEN = 5
FIELD_SIZE = 4
LIKE_OFFSET = 1 * FIELD_SIZE
FAV_OFFSET = 2 * FIELD_SIZE
USER_FOLLOWINGS_OFFSET = 0 * FIELD_SIZE
USER_FOLLOWERS_OFFSET = 1 * FIELD_SIZE
USER_POSTS_OFFSET = 2 * FIELD_SIZE
USER_LIKES_RECEIVED_OFFSET = 3 * FIELD_SIZE
USER_FAVS_RECEIVED_OFFSET = 4 * FIELD_SIZE
LIMIT = int(os.getenv("SOCIAL_HEAT_LIMIT", "1000"))
DRY_RUN = os.getenv("SOCIAL_HEAT_DRY_RUN", "").lower() in {"1", "true", "yes"}
WRITE_BITMAPS = os.getenv("SOCIAL_HEAT_BITMAPS", "1").lower() in {"1", "true", "yes"}
BITMAP_CHUNK_SIZE = 32_768
BITMAP_BYTES = BITMAP_CHUNK_SIZE // 8
SYNTHETIC_USER_BASE = int(os.getenv("SOCIAL_HEAT_USER_BASE", "1000000"))


def load_config() -> dict:
    config = yaml.safe_load((ROOT / "src/main/resources/application.yml").read_text(encoding="utf-8"))
    spring = config["spring"]
    datasource = spring["datasource"]
    redis = spring["data"]["redis"]

    match = re.search(r"jdbc:mysql://([^:/]+):(\d+)/([^?]+)", datasource["url"])
    if not match:
        raise RuntimeError("unsupported mysql url")

    return {
        "mysql_host": os.getenv("SOCIAL_HEAT_MYSQL_HOST", match.group(1)),
        "mysql_port": int(os.getenv("SOCIAL_HEAT_MYSQL_PORT", match.group(2))),
        "mysql_db": match.group(3),
        "mysql_user": datasource["username"],
        "mysql_password": datasource["password"],
        "redis_host": os.getenv("SOCIAL_HEAT_REDIS_HOST", redis["host"]),
        "redis_port": int(os.getenv("SOCIAL_HEAT_REDIS_PORT", redis["port"])),
        "redis_password": redis.get("password"),
    }


def resp_command(*parts: str | bytes) -> bytes:
    out = [f"*{len(parts)}\r\n".encode("utf-8")]
    for part in parts:
        if isinstance(part, str):
            part = part.encode("utf-8")
        out.append(f"${len(part)}\r\n".encode("utf-8"))
        out.append(part)
        out.append(b"\r\n")
    return b"".join(out)


def read_line(sock: socket.socket) -> bytes:
    chunks = []
    while True:
        b = sock.recv(1)
        if not b:
            raise RuntimeError("redis connection closed")
        chunks.append(b)
        if len(chunks) >= 2 and chunks[-2:] == [b"\r", b"\n"]:
            return b"".join(chunks[:-2])


def read_response(sock: socket.socket):
    line = read_line(sock)
    prefix = line[:1]
    payload = line[1:]
    if prefix == b"+":
        return payload.decode("utf-8")
    if prefix == b":":
        return int(payload)
    if prefix == b"$":
        size = int(payload)
        if size == -1:
            return None
        data = b""
        while len(data) < size + 2:
            data += sock.recv(size + 2 - len(data))
        return data[:size]
    if prefix == b"*":
        count = int(payload)
        return [read_response(sock) for _ in range(count)]
    if prefix == b"-":
        raise RuntimeError(payload.decode("utf-8", errors="replace"))
    raise RuntimeError(f"unknown redis response: {line!r}")


def redis_call(sock: socket.socket, *parts: str | bytes):
    sock.sendall(resp_command(*parts))
    return read_response(sock)


def redis_pipeline(sock: socket.socket, commands: list[tuple[str | bytes, ...]], batch_size: int = 500) -> None:
    for i in range(0, len(commands), batch_size):
        batch = commands[i : i + batch_size]
        sock.sendall(b"".join(resp_command(*cmd) for cmd in batch))
        for _ in batch:
            read_response(sock)


def int32_sds(*, like: int = 0, fav: int = 0) -> bytes:
    buf = bytearray(SDS_LEN * FIELD_SIZE)
    write_u32(buf, LIKE_OFFSET, like)
    write_u32(buf, FAV_OFFSET, fav)
    return bytes(buf)


def user_sds(*, followings: int, followers: int, posts: int, likes: int, favs: int) -> bytes:
    buf = bytearray(SDS_LEN * FIELD_SIZE)
    write_u32(buf, USER_FOLLOWINGS_OFFSET, followings)
    write_u32(buf, USER_FOLLOWERS_OFFSET, followers)
    write_u32(buf, USER_POSTS_OFFSET, posts)
    write_u32(buf, USER_LIKES_RECEIVED_OFFSET, likes)
    write_u32(buf, USER_FAVS_RECEIVED_OFFSET, favs)
    return bytes(buf)


def write_u32(buf: bytearray, offset: int, value: int) -> None:
    value = max(0, min(int(value), 0xFFFF_FFFF))
    buf[offset : offset + FIELD_SIZE] = struct.pack(">I", value)


def stable_int(seed: str, modulo: int) -> int:
    digest = hashlib.sha256(seed.encode("utf-8")).digest()
    return int.from_bytes(digest[:8], "big") % modulo


def heat_for_rank(post_id: int, rank: int, title: str | None) -> tuple[int, int]:
    jitter = stable_int(f"{post_id}:{title or ''}", 1000)

    if rank <= 8:
        base = 48_000 + stable_int(f"hot:{post_id}", 72_000)
    elif rank <= 30:
        base = 16_000 + stable_int(f"warm:{post_id}", 36_000)
    elif rank <= 120:
        base = 4_200 + stable_int(f"mid:{post_id}", 13_000)
    elif rank <= 400:
        base = 760 + stable_int(f"tail:{post_id}", 4_200)
    else:
        base = 120 + stable_int(f"long:{post_id}", 880)

    if title and any(word in title for word in ("RAG", "Redis", "MySQL", "Kafka", "Agent", "缓存")):
        base = int(base * 1.18)

    likes = max(100, base + jitter * 7)
    fav_ratio = 0.12 + (stable_int(f"fav:{post_id}", 18) / 100)
    favs = max(1, int(likes * fav_ratio))
    return likes, favs


def bitmap_chunks(start_user_id: int, count: int) -> dict[int, bytes]:
    chunks: dict[int, bytearray] = {}
    end_user_id = start_user_id + count
    current = start_user_id

    while current < end_user_id:
        chunk = current // BITMAP_CHUNK_SIZE
        bit = current % BITMAP_CHUNK_SIZE
        take = min(end_user_id - current, BITMAP_CHUNK_SIZE - bit)
        buf = chunks.setdefault(chunk, bytearray(BITMAP_BYTES))
        set_bit_range(buf, bit, take)
        current += take

    return {chunk: bytes(buf) for chunk, buf in chunks.items()}


def set_bit_range(buf: bytearray, start_bit: int, count: int) -> None:
    if count <= 0:
        return

    end_bit = start_bit + count
    first_byte = start_bit // 8
    last_byte = (end_bit - 1) // 8

    if first_byte == last_byte:
        for offset in range(start_bit, end_bit):
            byte_index = offset // 8
            bit_in_byte = 7 - (offset % 8)
            buf[byte_index] |= 1 << bit_in_byte
        return

    first_full_bit = (first_byte + 1) * 8
    for offset in range(start_bit, first_full_bit):
        buf[offset // 8] |= 1 << (7 - (offset % 8))

    full_start = first_byte + 1
    full_end = last_byte
    if full_start < full_end:
        buf[full_start:full_end] = b"\xff" * (full_end - full_start)

    for offset in range(last_byte * 8, end_bit):
        buf[offset // 8] |= 1 << (7 - (offset % 8))


def fetch_posts(config: dict) -> list[dict]:
    conn = pymysql.connect(
        host=config["mysql_host"],
        port=config["mysql_port"],
        user=config["mysql_user"],
        password=config["mysql_password"],
        database=config["mysql_db"],
        charset="utf8mb4",
    )
    try:
        with conn.cursor(pymysql.cursors.DictCursor) as cur:
            cur.execute(
                """
                SELECT id, creator_id, title
                FROM know_posts
                WHERE status = 'published'
                  AND visible = 'public'
                ORDER BY publish_time DESC, id DESC
                LIMIT %s
                """,
                (LIMIT,),
            )
            return list(cur.fetchall())
    finally:
        conn.close()


def main() -> None:
    config = load_config()
    posts = fetch_posts(config)
    if not posts:
        print("No public published posts found.")
        return

    author_posts = defaultdict(int)
    author_likes = defaultdict(int)
    author_favs = defaultdict(int)

    planned = []
    for rank, row in enumerate(posts, start=1):
        post_id = int(row["id"])
        creator_id = int(row["creator_id"])
        likes, favs = heat_for_rank(post_id, rank, row.get("title"))
        author_posts[creator_id] += 1
        author_likes[creator_id] += likes
        author_favs[creator_id] += favs
        planned.append((post_id, creator_id, likes, favs))

    if DRY_RUN:
        print_summary(planned, dry_run=True)
        return

    with socket.create_connection((config["redis_host"], config["redis_port"]), timeout=10) as sock:
        if config["redis_password"]:
            redis_call(sock, "AUTH", config["redis_password"])

        commands: list[tuple[str | bytes, ...]] = []
        for post_id, _creator_id, likes, favs in planned:
            commands.append(("SET", f"cnt:{SCHEMA_ID}:{ENTITY_TYPE}:{post_id}", int32_sds(like=likes, fav=favs)))
            if WRITE_BITMAPS:
                like_start = SYNTHETIC_USER_BASE + stable_int(f"like-users:{post_id}", 200_000)
                fav_start = SYNTHETIC_USER_BASE + 500_000 + stable_int(f"fav-users:{post_id}", 200_000)
                for chunk, payload in bitmap_chunks(like_start, likes).items():
                    commands.append(("SET", f"bm:like:{ENTITY_TYPE}:{post_id}:{chunk}", payload))
                for chunk, payload in bitmap_chunks(fav_start, favs).items():
                    commands.append(("SET", f"bm:fav:{ENTITY_TYPE}:{post_id}:{chunk}", payload))

        for author_id in sorted(author_posts):
            followers = 80 + stable_int(f"followers:{author_id}", 4200)
            followings = 12 + stable_int(f"followings:{author_id}", 180)
            commands.append(
                (
                    "SET",
                    f"ucnt:{author_id}",
                    user_sds(
                    followings=followings,
                    followers=followers,
                    posts=author_posts[author_id],
                    likes=author_likes[author_id],
                    favs=author_favs[author_id],
                ),
                )
            )

        redis_pipeline(sock, commands)

        # Drop feed fragments/pages so the next page load reads the new counters.
        for pattern in ("feed:public:*", "feed:item:*", "feed:mine:*"):
            keys = redis_call(sock, "KEYS", pattern)
            if keys:
                redis_call(sock, "DEL", *keys)

    print_summary(planned, dry_run=False)


def print_summary(planned: list[tuple[int, int, int, int]], *, dry_run: bool) -> None:
    likes = [p[2] for p in planned]
    favs = [p[3] for p in planned]
    label = "DRY RUN" if dry_run else "UPDATED"
    print(
        {
            "status": label,
            "posts": len(planned),
            "likes_min": min(likes),
            "likes_max": max(likes),
            "likes_avg": round(sum(likes) / len(likes), 1),
            "favs_min": min(favs),
            "favs_max": max(favs),
            "favs_avg": round(sum(favs) / len(favs), 1),
            "bitmap_facts": WRITE_BITMAPS,
        }
    )
    print("top_samples:")
    for post_id, creator_id, like, fav in planned[:10]:
        print(f"  post={post_id} creator={creator_id} like={like} fav={fav}")


if __name__ == "__main__":
    main()
