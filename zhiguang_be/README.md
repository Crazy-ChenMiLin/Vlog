# 知光 Zhiguang 后端

知光是一个知识获取与分享社区，支持知文发布、关注关系、点赞收藏、Feed 流、全文搜索、对象存储直传，以及面向知识库的 RAG 智能问答。项目目标不是简单 CRUD，而是围绕“内容社区 + 高并发读写 + AI 检索增强”做工程化拆分。

- 前端仓库：https://github.com/G-Pegasus/zhiguang_fe
- 后端仓库：https://github.com/Crazy-ChenMiLin/Vlog
- 后端技术栈：Java 21、Spring Boot、Spring Security、Spring AI、MyBatis、MySQL、Redis、Kafka、Elasticsearch、MinIO/OSS、Canal
- 前端技术栈：React、Vite

## 核心能力

| 模块 | 能力 |
|---|---|
| 认证系统 | JWT 双令牌，RS256 签名，Redis 刷新令牌白名单，支持会话撤销 |
| 发布系统 | Markdown/图片/视频对象存储直传，预签名上传，AI 摘要生成 |
| 计数系统 | Redis 位图/计数结构，Lua 原子更新，Kafka 异步聚合，异常时按需重建 |
| 关系系统 | 关注/取关，Outbox + Canal + Kafka 异步同步计数和缓存 |
| Feed 流 | Caffeine 本地缓存 + Redis 页面缓存 + Redis 片段缓存，热点探测和 single-flight |
| 搜索系统 | Elasticsearch 关键词检索、标签过滤、search_after 分页、completion suggester |
| RAG 问答 | 全库/单篇知识问答，多路召回、HyDE、RRF、rerank、调试评测闭环 |

## RAG 智能问答

知光 RAG 链路：

```text
用户问题
-> 问题改写 / standaloneQuestion
-> 原始向量召回 + HyDE 召回
-> RRF 融合候选集
-> NVIDIA rerank 模型重排
-> questionIntent + sectionType 轻量后处理
-> TopK 上下文给大模型流式回答
```

### 索引侧优化

原始 chunk 只有正文，rerank 模型难以区分“核心概念”“测试问题”“面试模板”等章节。后续将 chunk 从纯文本升级为结构化对象：

```java
record RagChunk(String text, String sectionTitle, String sectionType) {}
```

索引入库时将以下 metadata 写入 Elasticsearch：

- `title`
- `sectionTitle`
- `sectionType`
- `postId`
- `chunkId`
- `position`
- `indexVersion`

当前 `sectionType` 包括：

| sectionType | 含义 |
|---|---|
| `CONCEPT` | 核心概念 |
| `SOLUTION` | 解决方案/排查 |
| `INTERVIEW_TEMPLATE` | 面试回答模板 |
| `TEST_QUESTION` | 测试问题 |
| `BACKGROUND` | 背景 |
| `PITFALL` | 常见误区 |
| `OTHER` | 其他 |

### rerank 侧优化

rerank 输入从“正文”增强为：

```text
标题：...
章节：...
章节类型：...
正文：
...
```

同时加入轻量后处理：

```text
finalScore = rerankScore + sectionBoost(questionIntent, sectionType)
```

示例意图：

| questionIntent | 典型问题 | 偏好 |
|---|---|---|
| `EXPLAIN` | 是什么、有什么用、原理、区别 | 提升 `CONCEPT`，降低 `TEST_QUESTION` |
| `SOLUTION` | 怎么解决、怎么排查、注意什么 | 提升 `SOLUTION` / `CONCEPT` |
| `INTERVIEW` | 面试怎么回答 | 提升 `INTERVIEW_TEMPLATE` |
| `TEST` | 测试问题、评估、召回率 | 提升 `TEST_QUESTION` |

### 评测结果

使用 20 个问题评测 fused Top1 与 rerank Top1 的章节适配度：

```text
变好数：10
变差数：1
不变数：9
优化率：50%
劣化率：5%
净优化率：45%
```

RRF 反例诊断：

```text
TEST_QUESTION rerankScore = 17.0625, boost = -0.35, finalScore = 16.7125
CONCEPT       rerankScore = 15.9297, boost = +0.35, finalScore = 16.2797
```

结论：当前方案有效，但仍需继续暴露分数并微调权重，避免个别解释型问题被测试题列表抢占 Top1。

相关脚本：

```text
scripts/codex_compare_rag_debug.py
scripts/codex_reindex_rag_test_posts.py
```

## 本地运行

要求：

- JDK 21
- Maven 3.9+
- MySQL 8
- Redis 7
- Kafka
- Elasticsearch 8
- MinIO 或兼容 OSS

启动：

```bash
mvn spring-boot:run
```

测试：

```bash
mvn test
mvn -DskipTests compile
```

## Docker 编排

仓库提供基础 Docker 编排模板：

```text
Dockerfile
docker-compose.yml
.env.example
```

首次部署：

```bash
cp .env.example .env
# 修改 .env 中的数据库、Redis、Kafka、ES、MinIO、AI API Key 等配置
docker compose up -d --build
```

查看日志：

```bash
docker compose logs -f zhiguang-be
```

重新部署后端：

```bash
docker compose up -d --build zhiguang-be
```

### 云服务器部署注意

1. 不要把真实 `.env`、密钥、API Key 提交到 Git。
2. MySQL、Redis、Kafka、Elasticsearch、MinIO 可使用本 compose 启动，也可替换成 1Panel 已有服务。
3. 如果使用外部 Kafka，注意 `advertised.listeners` 必须和后端实际连接地址一致。
4. Kafka 单机测试环境不要把 `message.max.bytes` 等配置放到几百 MB，容易造成 broker 内存压力。
5. RAG 相关依赖包括 Elasticsearch 向量索引、OpenAI-compatible embedding、NVIDIA rerank API，部署前必须确认网络和 Key 可用。

## GitHub Actions 自动部署

仓库提供 `.github/workflows/deploy.yml`，推送到 `main` 后会自动：

```text
checkout 代码
-> Maven compile 校验
-> 打包源码
-> SSH 上传到云服务器
-> 写入服务器 .env
-> docker compose up -d --build
```

需要在 GitHub 仓库的 `Settings -> Secrets and variables -> Actions` 中配置：

| Secret | 含义 |
|---|---|
| `SERVER_HOST` | 云服务器 IP 或域名 |
| `SERVER_PORT` | SSH 端口，默认可填 `22` |
| `SERVER_USER` | SSH 用户，例如 `root` |
| `SERVER_SSH_KEY` | 私钥内容，不是 `.pub` 公钥 |
| `DEPLOY_PATH` | 服务器部署目录，例如 `/opt/zhiguang/zhiguang_be` |
| `PROD_ENV` | 生产 `.env` 完整内容，可参考 `.env.example` |

服务器需要提前安装：

```text
Docker
Docker Compose v2
```

如果服务器使用 1Panel 已有 MySQL、Redis、Kafka、Elasticsearch、MinIO，可以在 `PROD_ENV` 中把连接地址改成对应容器网络地址或服务器内网地址，并按需调整 `docker-compose.yml` 中的依赖服务。

## 主要接口

RAG debug：

```text
GET /api/v1/knowposts/qa/debug?question=...&topK=10
```

单篇重建索引：

```text
POST /api/v1/knowposts/{id}/rag/reindex
```

全库问答流：

```text
GET /api/v1/knowposts/qa/stream?question=...&topK=5
```

更多接口见：

```text
docs/
```

## 项目截图

<div style="display: flex; gap: 10px; flex-wrap: wrap;">
  <img src="https://free.picui.cn/free/2026/03/29/69c8db2c32d74.png" width="600" />
  <img src="https://free.picui.cn/free/2026/03/29/69c8db2ead009.png" width="600" />
  <img src="https://free.picui.cn/free/2026/03/29/69c8db2b93e32.png" width="600" />
</div>
