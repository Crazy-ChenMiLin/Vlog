# Graphify-lite Seed

这个目录用于先跑通 Neo4j 最小闭环：

1. 写入面试知识概念节点。
2. 写入概念关系。
3. 根据问题识别实体。
4. 从 Neo4j 查出 GraphContext。

当前脚本不会接入 RAG 主链路，也不会写入 chunkId。第一版只验证：

```text
Neo4j 负责知识关系导航；
ES / VectorStore 后续仍负责 chunk 证据召回。
```

## 安装依赖

```powershell
python -m pip install -r scripts\graph\requirements.txt
```

## 配置连接

脚本读取这些环境变量：

```text
NEO4J_URI
NEO4J_USERNAME
NEO4J_PASSWORD
```

`application.yml` 中也有对应的 Spring Boot 配置：

```yaml
spring:
  neo4j:
    uri: ${NEO4J_URI:bolt://100.83.242.114:7687}
    authentication:
      username: ${NEO4J_USERNAME:neo4j}
      password: ${NEO4J_PASSWORD:...}
```

## 写入并验证 Redis 缓存对比关系

```powershell
$env:NEO4J_PASSWORD='你的密码'
python scripts\graph\seed_interview_graph.py
```

## 验证 token 关系问题

```powershell
$env:NEO4J_PASSWORD='你的密码'
python scripts\graph\seed_interview_graph.py --verify-query '知光项目里，Redis 主要保存 access token 还是 refresh token?'
```

期望返回的核心关系：

```text
Redis - STORES -> Refresh Token
Access Token - COMPARE_WITH -> Refresh Token
JwtService - ISSUES -> Access Token
JwtService - ISSUES -> Refresh Token
```
