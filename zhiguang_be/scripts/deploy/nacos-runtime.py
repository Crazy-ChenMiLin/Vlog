#!/usr/bin/env python3
"""Check Nacos before deployment, or migrate an existing backend once."""

import argparse
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import tempfile
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


DATA_ID = "zhiguang-runtime.yaml"
GROUP = "ZHIGUANG_GROUP"
RUNTIME_KEYS = {
    "SERVER_PORT": "server.port",
    "SPRING_DATASOURCE_URL": "spring.datasource.url",
    "SPRING_DATA_REDIS_HOST": "spring.data.redis.host",
    "SPRING_DATA_REDIS_PORT": "spring.data.redis.port",
    "SPRING_KAFKA_BOOTSTRAP_SERVERS": "spring.kafka.bootstrap-servers",
    "SPRING_ELASTICSEARCH_URIS": "spring.elasticsearch.uris",
    "SPRING_NEO4J_URI": "spring.neo4j.uri",
    "SPRING_MAIL_HOST": "spring.mail.host",
    "SPRING_MAIL_PORT": "spring.mail.port",
    "SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH": "spring.mail.properties.mail.smtp.auth",
    "SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_ENABLE": "spring.mail.properties.mail.smtp.ssl.enable",
    "SPRING_AI_VECTORSTORE_ELASTICSEARCH_INDEX_NAME": "spring.ai.vectorstore.elasticsearch.index-name",
    "SPRING_AI_OPENAI_CHAT_BASE_URL": "spring.ai.openai.chat.base-url",
    "SPRING_AI_OPENAI_CHAT_COMPLETIONS_PATH": "spring.ai.openai.chat.completions-path",
    "SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL": "spring.ai.openai.chat.options.model",
    "SPRING_AI_OPENAI_CHAT_OPTIONS_TEMPERATURE": "spring.ai.openai.chat.options.temperature",
    "SPRING_AI_OPENAI_EMBEDDING_BASE_URL": "spring.ai.openai.embedding.base-url",
    "SPRING_AI_OPENAI_EMBEDDING_OPTIONS_MODEL": "spring.ai.openai.embedding.options.model",
    "SPRING_AI_OPENAI_EMBEDDING_OPTIONS_DIMENSIONS": "spring.ai.openai.embedding.options.dimensions",
    "AUTH_MAIL_ENABLED": "auth.mail.enabled",
    "AUTH_MAIL_FROM": "auth.mail.from",
    "GITHUB_REDIRECT_URI": "github.redirect-uri",
    "CAMPUS_REDIRECT_URI": "campus.redirect-uri",
    "CAMPUS_AUTHORIZATION_ENDPOINT": "campus.authorization-endpoint",
    "CAMPUS_TOKEN_ENDPOINT": "campus.token-endpoint",
    "CAMPUS_JWKS_URI": "campus.jwks-uri",
    "CAMPUS_SCOPES": "campus.scopes",
    "MINIO_ENDPOINT": "oss.endpoint",
    "MINIO_BUCKET": "oss.bucket",
    "MINIO_PUBLIC_DOMAIN": "oss.public-domain",
    "OSS_ENDPOINT": "oss.endpoint",
    "OSS_BUCKET": "oss.bucket",
    "OSS_PUBLIC_DOMAIN": "oss.public-domain",
    "OSS_PRESIGN_ENDPOINT": "oss.presign-endpoint",
    "GITHUB_EXTERNAL_SEARCH_REPOSITORY": "rag.external.github-official-go.repository",
    "GITHUB_EXTERNAL_SEARCH_BASE_URL": "rag.external.github-official-go.base-url",
    "AGENT_PITHAGORAS_BASE_URL": "agent.pithagoras-base-url",
    "AGENT_TIMEOUT_SECONDS": "agent.timeout-seconds",
    "AGENT_POOL_SIZE": "agent.pool-size",
    "AGENT_QUEUE_CAPACITY": "agent.queue-capacity",
    "AGENT_COMMENT_USER_ID": "agent.comment-user-id",
    "LOGGING_LOGSTASH_HOST": "logging.logstash.host",
    "LOGGING_LOGSTASH_PORT": "logging.logstash.port",
    "VIBE_PAY_PRODUCT_ID": "VIBE_PAY_PRODUCT_ID",
    "VIBE_PAY_CALLBACK_URL": "VIBE_PAY_CALLBACK_URL",
}
RUNTIME_KEYS.update({
    f"CANAL_{name.upper().replace('-', '_')}": f"canal.{name}"
    for name in ("enabled", "host", "port", "destination", "filter", "batch-size", "interval-ms")
})
RUNTIME_KEYS.update({
    f"RAG_LLM_{node.upper().replace('-', '_')}_MODEL": f"rag.llm.{node}-model"
    for node in ("default", "planner", "graph", "evidence", "rewrite", "hyde", "direct-answer", "final-answer")
})
RUNTIME_KEYS.update({
    f"RAG_RERANK_{name.upper().replace('-', '_')}": f"rag.rerank.{name}"
    for name in ("enabled", "base-url", "model", "path")
})
SECRET_KEYS = {
    "SPRING_DATASOURCE_USERNAME": "spring.datasource.username",
    "SPRING_DATASOURCE_PASSWORD": "spring.datasource.password",
    "SPRING_DATA_REDIS_PASSWORD": "spring.data.redis.password",
    "SPRING_ELASTICSEARCH_USERNAME": "spring.elasticsearch.username",
    "SPRING_ELASTICSEARCH_PASSWORD": "spring.elasticsearch.password",
    "SPRING_NEO4J_AUTHENTICATION_USERNAME": "spring.neo4j.authentication.username",
    "SPRING_NEO4J_AUTHENTICATION_PASSWORD": "spring.neo4j.authentication.password",
    "SPRING_MAIL_USERNAME": "spring.mail.username",
    "SPRING_MAIL_PASSWORD": "spring.mail.password",
    "SPRING_AI_OPENAI_API_KEY": "spring.ai.openai.api-key",
    "SPRING_AI_OPENAI_CHAT_API_KEY": "spring.ai.openai.chat.api-key",
    "SPRING_AI_OPENAI_EMBEDDING_API_KEY": "spring.ai.openai.embedding.api-key",
    "AUTH_JWT_PRIVATE_KEY": "auth.jwt.private-key",
    "AUTH_JWT_PUBLIC_KEY": "auth.jwt.public-key",
    "GITHUB_CLIENT_ID": "github.client-id",
    "GITHUB_CLIENT_SECRET": "github.client-secret",
    "CAMPUS_CLIENT_ID": "campus.client-id",
    "CAMPUS_CLIENT_SECRET": "campus.client-secret",
    "MINIO_ACCESS_KEY": "oss.access-key-id",
    "MINIO_SECRET_KEY": "oss.access-key-secret",
    "OSS_ACCESS_KEY_ID": "oss.access-key-id",
    "OSS_ACCESS_KEY_SECRET": "oss.access-key-secret",
    "CANAL_USERNAME": "canal.username",
    "CANAL_PASSWORD": "canal.password",
    "RAG_RERANK_API_KEY": "rag.rerank.api-key",
    "GITHUB_EXTERNAL_SEARCH_TOKEN": "rag.external.github-official-go.token",
    "AGENT_PITHAGORAS_SECRET": "agent.pithagoras-secret",
    "NACOS_USERNAME": "rag.prompt.username",
    "NACOS_PASSWORD": "rag.prompt.password",
    **{name: name for name in (
        "BENCHMARK_TOKEN", "AGENT_INTERNAL_TOKEN", "ALIPAY_APP_ID",
        "ALIPAY_PRIVATE_KEY", "ALIPAY_PUBLIC_KEY",
    )},
}
REQUIRED_RUNTIME_KEYS = ("spring.datasource.url", "spring.data.redis.host", "spring.kafka.bootstrap-servers")


def nacos_request(address, path, *, query=None, form=None, token=None):
    headers = {"accessToken": token} if token else {}
    url = address + path
    if query:
        url += "?" + urlencode(query)
    request = Request(url, data=urlencode(form).encode() if form is not None else None, headers=headers)
    with urlopen(request, timeout=10) as response:
        return json.load(response)


def configuration(address, namespace, token):
    result = nacos_request(address, "/nacos/v3/client/cs/config", token=token, query={
        "namespaceId": namespace, "groupName": GROUP, "dataId": DATA_ID,
    })
    if result.get("code") != 0:
        return None
    return (result.get("data") or {}).get("content")


def verify_runtime(config):
    import yaml

    properties = flatten(yaml.safe_load(config) or {})
    for key in REQUIRED_RUNTIME_KEYS:
        if not properties.get(key):
            raise ValueError(f"Nacos configuration is missing {key}")


def flatten(document, prefix=""):
    values = {}
    for key, value in document.items():
        path = prefix + key
        if isinstance(value, dict):
            values.update(flatten(value, path + "."))
        else:
            values[path] = value
    return values


def legacy_environment(args):
    if args.container:
        container = json.loads(subprocess.check_output(["docker", "inspect", args.container]))[0]
        return dict(entry.split("=", 1) for entry in container["Config"]["Env"])

    with tempfile.TemporaryDirectory() as directory:
        compose_file = Path(directory) / "compose.json"
        env_file = str(args.legacy_env.resolve())
        compose_file.write_text(json.dumps({
            "services": {"legacy": {"image": "scratch", "env_file": [env_file]}},
        }), encoding="utf-8")
        model = json.loads(subprocess.check_output([
            "docker", "compose", "-f", str(compose_file), "--env-file", env_file,
            "config", "--format", "json",
        ]))
    return model["services"]["legacy"].get("environment", {})


def write_private(path, content):
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    try:
        file = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        if path.read_text(encoding="utf-8") == content:
            return
        raise ValueError(f"{path.name} already exists with different content") from None
    with os.fdopen(file, "w", encoding="utf-8", newline="\n") as stream:
        stream.write(content)


def migrate(args, values, address, namespace, token):
    import yaml

    if namespace not in {"zhiguang-dev", "zhiguang-prod"}:
        raise ValueError("Migration requires zhiguang-dev or zhiguang-prod")
    try:
        existing = configuration(address, namespace, token)
    except HTTPError as error:
        if error.code != 404:
            raise
        existing = None
    if existing:
        raise ValueError(f"{namespace}/{DATA_ID} already exists")

    previous = configuration(address, "public", token)
    if not previous:
        raise ValueError("Current public RAG configuration could not be loaded")
    runtime = flatten(yaml.safe_load(previous))
    runtime.pop("rag.rerank.api-key", None)
    runtime.pop("rag.external.github-official-go.token", None)
    for name, key in RUNTIME_KEYS.items():
        value = values.get(name)
        if not value:
            continue
        if re.search(r"://[^/\s]*@|[?&](?:[^=&]*(?:token|password|secret|api.?key))=", value, re.I):
            raise ValueError(f"{name} appears to contain a credential")
        runtime[key] = value
    for key in REQUIRED_RUNTIME_KEYS:
        if not runtime.get(key):
            raise ValueError(f"Legacy configuration does not provide {key}")
    # Compose publishes the dev container's port 8080.
    if namespace == "zhiguang-dev":
        runtime.pop("server.port", None)

    secrets = {key: values[name] for name, key in SECRET_KEYS.items() if values.get(name)}
    if namespace == "zhiguang-dev":
        for key in ("auth.jwt.private-key", "auth.jwt.public-key"):
            if secrets.get(key, "").startswith("file:/app/keys/"):
                secrets.pop(key)
    write_private(args.output / "backend-secrets.yml", yaml.safe_dump(secrets, sort_keys=True))
    if args.container:
        java_options = [
            option for name in ("JAVA_OPTS", "JAVA_TOOL_OPTIONS")
            for option in shlex.split(values.get(name, ""))
        ]
        write_private(args.output / "java.options", "\n".join(json.dumps(option) for option in java_options) + "\n")

    namespaces = nacos_request(address, "/nacos/v3/admin/core/namespace/list", token=token)
    if namespaces.get("code") != 0:
        raise ValueError("Could not inspect Nacos namespaces")
    if not any(row["namespace"] == namespace for row in namespaces["data"]):
        created = nacos_request(address, "/nacos/v3/admin/core/namespace", token=token, form={
            "namespaceId": namespace, "namespaceName": namespace,
        })
        if created.get("code") != 0 or not created.get("data"):
            raise ValueError(f"Could not create namespace {namespace}")
    published = nacos_request(address, "/nacos/v3/admin/cs/config", token=token, form={
        "namespaceId": namespace, "groupName": GROUP, "dataId": DATA_ID, "type": "yaml",
        "content": yaml.safe_dump(runtime, sort_keys=True),
    })
    if published.get("code") != 0:
        raise ValueError("Nacos rejected the initial runtime configuration")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("check")
    migration = commands.add_parser("migrate")
    source = migration.add_mutually_exclusive_group(required=True)
    source.add_argument("--legacy-env", type=Path)
    source.add_argument("--container")
    migration.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    try:
        values = legacy_environment(args) if args.command == "migrate" else {}
        address = os.environ.get("NACOS_SERVER_ADDR") or values.get("NACOS_SERVER_ADDR")
        namespace = os.environ.get("NACOS_NAMESPACE")
        username = os.environ.get("NACOS_USERNAME") or values.get("NACOS_USERNAME")
        password = os.environ.get("NACOS_PASSWORD") or values.get("NACOS_PASSWORD")
        if not address or not namespace or namespace == "public":
            raise ValueError("Set NACOS_SERVER_ADDR and a dedicated NACOS_NAMESPACE")
        if not address.startswith(("http://", "https://")):
            address = "http://" + address
        address = address.rstrip("/")
        token = None
        if username and password:
            auth = nacos_request(address, "/nacos/v3/auth/user/login", form={
                "username": username, "password": password,
            })
            token = auth.get("accessToken")
            if not token:
                raise ValueError("Nacos login returned no access token")
        if args.command == "migrate":
            if not token:
                raise ValueError("Initial migration requires a Nacos publisher account")
            migrate(args, values, address, namespace, token)
        else:
            config = configuration(address, namespace, token)
            if not config:
                raise ValueError(f"Missing {namespace}/{GROUP}/{DATA_ID}")
            verify_runtime(config)
        print(f"Verified Nacos configuration path {namespace}/{GROUP}/{DATA_ID}")
    except ValueError as error:
        parser.exit(1, f"{error}\n")
    except Exception as error:
        parser.exit(1, f"Nacos {args.command} failed ({type(error).__name__}).\n")


if __name__ == "__main__":
    main()
