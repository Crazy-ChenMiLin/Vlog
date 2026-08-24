#!/usr/bin/env python3
"""Shared HTTP helpers for the standalone T2Retrieval scripts."""

from __future__ import annotations

import base64
import json
import os
import random
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any, Iterable


class HttpError(RuntimeError):
    """HTTP request failed after retries."""


def env_first(*names: str, default: str = "") -> str:
    for name in names:
        value = os.getenv(name)
        if value:
            return value
    return default


def chunked(items: list[Any], size: int) -> Iterable[list[Any]]:
    for start in range(0, len(items), size):
        yield items[start : start + size]


@dataclass(frozen=True)
class JsonHttpClient:
    base_url: str
    username: str = ""
    password: str = ""
    bearer_token: str = ""
    timeout_seconds: float = 60.0
    max_retries: int = 6

    def request(
        self,
        method: str,
        path: str,
        body: Any | None = None,
        *,
        content_type: str = "application/json",
    ) -> Any:
        url = self.base_url.rstrip("/") + "/" + path.lstrip("/")
        data: bytes | None
        if body is None:
            data = None
        elif isinstance(body, bytes):
            data = body
        else:
            data = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")

        last_error: Exception | None = None
        for attempt in range(self.max_retries + 1):
            headers = {"Accept": "application/json"}
            if data is not None:
                headers["Content-Type"] = content_type
            if self.bearer_token:
                headers["Authorization"] = "Bearer " + self.bearer_token
            elif self.username:
                raw = f"{self.username}:{self.password}".encode("utf-8")
                headers["Authorization"] = "Basic " + base64.b64encode(raw).decode("ascii")
            request = urllib.request.Request(url, data=data, headers=headers, method=method.upper())
            try:
                with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
                    payload = response.read()
                    return json.loads(payload) if payload else {}
            except urllib.error.HTTPError as error:
                detail = error.read().decode("utf-8", errors="replace")[:2000]
                last_error = HttpError(f"HTTP {error.code} {method} {path}: {detail or error.reason}")
                if error.code not in {408, 409, 429, 500, 502, 503, 504}:
                    raise last_error from error
                retry_after = error.headers.get("Retry-After")
                delay = float(retry_after) if retry_after and retry_after.isdigit() else _backoff(attempt)
            except (urllib.error.URLError, TimeoutError) as error:
                last_error = error
                delay = _backoff(attempt)
            if attempt >= self.max_retries:
                break
            time.sleep(delay)
        raise HttpError(f"Request failed after retries: {method} {path}: {last_error}")


def _backoff(attempt: int) -> float:
    return min(30.0, (2**attempt) + random.random())


def es_client(args: Any) -> JsonHttpClient:
    return JsonHttpClient(
        base_url=args.es_url,
        username=args.es_username,
        password=args.es_password,
        timeout_seconds=args.timeout_seconds,
        max_retries=args.max_retries,
    )


def add_es_arguments(parser: Any) -> None:
    parser.add_argument(
        "--es-url",
        default=env_first("T2_ES_URL", default="http://127.0.0.1:9200"),
        help="Elasticsearch base URL (env: T2_ES_URL).",
    )
    parser.add_argument(
        "--index",
        default=env_first("T2_ES_INDEX", default="zhiguang-ai-index"),
        help="Target Elasticsearch index (env: T2_ES_INDEX).",
    )
    parser.add_argument(
        "--es-username",
        default=env_first("T2_ES_USERNAME", "ELASTICSEARCH_USERNAME"),
        help="Optional Elasticsearch username.",
    )
    parser.add_argument(
        "--es-password",
        default=env_first("T2_ES_PASSWORD", "ELASTICSEARCH_PASSWORD"),
        help="Optional Elasticsearch password. Prefer the environment variable.",
    )
    parser.add_argument("--timeout-seconds", type=float, default=120.0)
    parser.add_argument("--max-retries", type=int, default=6)


def bulk_request(client: JsonHttpClient, operations: list[dict[str, Any]]) -> dict[str, Any]:
    lines = [json.dumps(item, ensure_ascii=False, separators=(",", ":")) for item in operations]
    payload = ("\n".join(lines) + "\n").encode("utf-8")
    response = client.request("POST", "/_bulk", payload, content_type="application/x-ndjson")
    if not isinstance(response, dict):
        raise HttpError("Elasticsearch bulk response is not a JSON object")
    return response


def bulk_failures(response: dict[str, Any]) -> list[dict[str, Any]]:
    failures: list[dict[str, Any]] = []
    for item in response.get("items", []):
        if not isinstance(item, dict) or not item:
            continue
        result = next(iter(item.values()))
        if isinstance(result, dict) and int(result.get("status", 500)) >= 300:
            failures.append(result)
    return failures


def require_index(client: JsonHttpClient, index: str, expected_dims: int = 4096) -> dict[str, Any]:
    mapping = client.request("GET", f"/{index}/_mapping")
    if index not in mapping:
        raise RuntimeError(f"Elasticsearch index does not exist: {index}")
    properties = mapping[index].get("mappings", {}).get("properties", {})
    embedding = properties.get("embedding", {})
    if embedding.get("type") != "dense_vector":
        raise RuntimeError(f"{index}.embedding is not mapped as dense_vector")
    dims = int(embedding.get("dims", 0))
    if dims != expected_dims:
        raise RuntimeError(f"{index}.embedding dims={dims}, expected {expected_dims}")
    return mapping[index]


class NvidiaEmbeddingClient:
    def __init__(
        self,
        *,
        api_key: str,
        base_url: str,
        model: str,
        dimensions: int,
        timeout_seconds: float,
        max_retries: int,
    ) -> None:
        if not api_key:
            raise RuntimeError(
                "Missing NVIDIA API key. Set NVIDIA_API_KEY or SPRING_AI_OPENAI_EMBEDDING_API_KEY."
            )
        self.model = model
        self.dimensions = dimensions
        self.http = JsonHttpClient(
            base_url=base_url,
            bearer_token=api_key,
            timeout_seconds=timeout_seconds,
            max_retries=max_retries,
        )

    def embed(self, texts: list[str], input_type: str) -> list[list[float]]:
        response = self.http.request(
            "POST",
            "/v1/embeddings",
            {
                "input": texts,
                "model": self.model,
                "input_type": input_type,
                "encoding_format": "float",
                "truncate": "END",
            },
        )
        data = response.get("data") if isinstance(response, dict) else None
        if not isinstance(data, list) or len(data) != len(texts):
            raise RuntimeError(f"Embedding response size mismatch: expected {len(texts)}")
        ordered = sorted(data, key=lambda item: int(item.get("index", 0)))
        vectors: list[list[float]] = []
        for item in ordered:
            vector = item.get("embedding")
            if not isinstance(vector, list) or len(vector) != self.dimensions:
                length = len(vector) if isinstance(vector, list) else -1
                raise RuntimeError(f"Embedding dimensions mismatch: got {length}, expected {self.dimensions}")
            vectors.append(vector)
        return vectors


def add_nvidia_arguments(parser: Any) -> None:
    parser.add_argument(
        "--nvidia-api-key",
        default=env_first("NVIDIA_API_KEY", "SPRING_AI_OPENAI_EMBEDDING_API_KEY"),
        help="NVIDIA API key. Prefer NVIDIA_API_KEY in the environment.",
    )
    parser.add_argument(
        "--embedding-url",
        default=env_first("T2_EMBEDDING_URL", default="https://integrate.api.nvidia.com"),
    )
    parser.add_argument(
        "--embedding-model",
        default=env_first("T2_EMBEDDING_MODEL", default="nvidia/nv-embed-v1"),
    )
    parser.add_argument("--embedding-dimensions", type=int, default=4096)

