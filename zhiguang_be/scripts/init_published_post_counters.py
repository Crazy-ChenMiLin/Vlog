import re
import socket
from pathlib import Path

import pymysql
import yaml


SCHEMA_ID = "v1"
ENTITY_TYPE = "knowpost"
ZERO_SDS = bytes(20)


def load_config():
    config = yaml.safe_load(Path("src/main/resources/application.yml").read_text(encoding="utf-8"))
    spring = config["spring"]
    datasource = spring["datasource"]
    redis = spring["data"]["redis"]

    match = re.search(r"jdbc:mysql://([^:/]+):(\d+)/([^?]+)", datasource["url"])
    if not match:
        raise RuntimeError("unsupported mysql url")

    return {
        "mysql_host": match.group(1),
        "mysql_port": int(match.group(2)),
        "mysql_db": match.group(3),
        "mysql_user": datasource["username"],
        "mysql_password": datasource["password"],
        "redis_host": redis["host"],
        "redis_port": int(redis["port"]),
        "redis_password": redis.get("password"),
    }


def resp_command(*parts):
    out = [f"*{len(parts)}\r\n".encode("utf-8")]
    for part in parts:
        if isinstance(part, str):
            part = part.encode("utf-8")
        out.append(f"${len(part)}\r\n".encode("utf-8"))
        out.append(part)
        out.append(b"\r\n")
    return b"".join(out)


def read_line(sock):
    chunks = []
    while True:
        b = sock.recv(1)
        if not b:
            raise RuntimeError("redis connection closed")
        chunks.append(b)
        if b"".join(chunks[-2:]) == b"\r\n":
            return b"".join(chunks)[:-2]


def read_response(sock):
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
    if prefix == b"-":
        raise RuntimeError(payload.decode("utf-8", errors="replace"))
    raise RuntimeError(f"unknown redis response: {line!r}")


def redis_call(sock, *parts):
    sock.sendall(resp_command(*parts))
    return read_response(sock)


def fetch_published_post_ids(config):
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
                SELECT id
                FROM know_posts
                WHERE status = 'published'
                  AND visible = 'public'
                ORDER BY publish_time DESC, id DESC
                """
            )
            return [str(row[0]) for row in cur.fetchall()]
    finally:
        conn.close()


def main():
    config = load_config()
    post_ids = fetch_published_post_ids(config)

    created = 0
    existed = 0
    with socket.create_connection((config["redis_host"], config["redis_port"]), timeout=10) as sock:
        if config["redis_password"]:
            redis_call(sock, "AUTH", config["redis_password"])

        for post_id in post_ids:
            key = f"cnt:{SCHEMA_ID}:{ENTITY_TYPE}:{post_id}"
            result = redis_call(sock, "SETNX", key, ZERO_SDS)
            if result == 1:
                created += 1
            else:
                existed += 1

    print(f"published_posts={len(post_ids)} created={created} existed={existed}")


if __name__ == "__main__":
    main()
