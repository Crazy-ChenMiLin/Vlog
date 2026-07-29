import pymysql, boto3, json
from botocore.config import Config

MYSQL = dict(host="100.83.242.114", port=3306, user="root", password="czqCZQ197623@",
            database="zhiguang_auth", charset="utf8mb4")
MINIO = dict(endpoint_url="http://100.83.242.114:9000", aws_access_key_id="minio_fjTXH3",
             aws_secret_access_key="czqCZQ197623", region_name="us-east-1")

# --- MySQL ---
conn = pymysql.connect(**MYSQL)
try:
    with conn.cursor() as cur:
        cur.execute("SELECT COUNT(*) FROM know_posts")
        total = cur.fetchone()[0]
        cur.execute("SELECT COUNT(*) FROM know_posts WHERE status='published' AND visible='public'")
        pub = cur.fetchone()[0]
        cur.execute("SELECT id, nickname FROM users ORDER BY id LIMIT 10")
        users = cur.fetchall()
        cur.execute("SELECT COUNT(*) FROM users")
        user_count = cur.fetchone()[0]
finally:
    conn.close()

print("know_posts total:", total)
print("published&public:", pub)
print("users total:", user_count)
print("sample users:", users)

# --- MinIO test ---
s3 = boto3.client("s3", endpoint_url=MINIO["endpoint_url"],
                  aws_access_key_id=MINIO["aws_access_key_id"],
                  aws_secret_access_key=MINIO["aws_secret_access_key"],
                  region_name="us-east-1",
                  config=Config(s3={"addressing_style": "path"}))
try:
    s3.put_object(Bucket="zhiguang", Key="__probe__/test.txt", Body=b"hello")
    s3.delete_object(Bucket="zhiguang", Key="__probe__/test.txt")
    print("MinIO put OK")
except Exception as e:
    print("MinIO ERROR:", repr(e))
