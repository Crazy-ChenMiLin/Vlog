# 知光知识社区

知光是一个面向知识内容发布、学习交流和智能问答的社区项目。项目采用前后端分离架构，后端负责认证、内容、关系链、评论、计数、搜索、对象存储和 RAG 问答能力，前端提供知识流、内容详情、发布编辑、个人主页、搜索和 AI 对话等页面。

当前仓库是前后端合仓：

- 后端：`zhiguang_be`，Java 21 + Spring Boot 3。
- 前端：`zhiguang_fe/zhiguang_fe-main`，React + Vite + TypeScript。

## 功能概览

- 用户认证：基于 Spring Security 的 JWT 认证，支持访问令牌和刷新令牌；支持密码登录、GitHub OAuth 与校园 CQUT-Auth OIDC 三种登录方式。
- 知识发布：支持草稿创建、内容上传确认、元数据编辑、发布、置顶、可见性控制和删除。
- 评论互动：支持对知识内容发表评论、回复、点赞以及分页加载。
- 对象存储：通过后端生成预签名信息，前端直传内容、图片等资源到对象存储。
- 首页 Feed：面向知识内容列表展示，结合本地缓存、Redis 缓存和热点探测优化读取。
- 点赞收藏：支持点赞、取消点赞、收藏、取消收藏和计数查询。
- 用户关系：支持关注、取关、粉丝数、关注数以及关系状态维护。
- 搜索系统：基于 Elasticsearch 实现内容检索、标签过滤和搜索建议。
- AI 能力：支持知识文章摘要生成、单篇 / 全局 RAG 问答、流式输出（SSE）、向量索引重建、关系图增强与证据校验。
- 评测体系：内置 RAG Benchmark 服务与附属脚本，可对多场景问答质量做可复现的评测分析。
- 事件驱动：使用 Kafka、Canal 和 Outbox 模式处理计数聚合、关系变更、搜索索引等异步任务。

## 技术栈

### 后端

- Java 21
- Spring Boot 3.2.4
- Spring Security
- Spring AI
- MyBatis
- MySQL 8
- Redis / Redisson
- Kafka
- Canal
- Elasticsearch
- Caffeine
- Nacos Config（配置中心）
- MinIO / S3 兼容对象存储
- Maven

### 前端

- React 18
- TypeScript
- Vite 5
- React Router
- React Markdown
- remark-gfm（Markdown 表格等 GFM 语法扩展）
- CSS Modules

## 目录结构

```text
.
├── zhiguang_be
│   ├── docs               # 后端接口文档与 SQL
│   ├── scripts
│   │   ├── deploy         # 内网后端、公网前端与 Nacos 部署脚本
│   │   ├── AUTO_Benchwork # RAG Benchmark 数据集、流水线与测试
│   │   ├── rag-eval       # RAG/Graph A/B 评测工具
│   │   ├── graph          # 图数据工具
│   │   └── seed_*.py      # 造数脚本（内容、关系、热度、点赞计数等）
│   ├── src/main/java      # 后端业务代码
│   ├── src/main/resources # MyBatis mapper、密钥、配置等资源
│   └── pom.xml
├── zhiguang_fe
│   └── zhiguang_fe-main
│       ├── docs           # 前端接口契约
│       ├── public
│       ├── src            # 前端页面、组件、服务、类型和功能模块
│       └── package.json
└── README.md
```

## 后端模块

- `auth`：登录、注册、验证码、JWT 签发、刷新令牌、GitHub OAuth 与校园 OIDC 回调、登录日志与审计。
- `profile`：用户资料、头像、个人主页信息。
- `knowpost`：知识内容草稿、发布、详情、列表、摘要生成和 RAG 入口。
- `comment`：评论、回复、点赞与分页加载。
- `storage`：对象存储预签名上传、公开访问地址生成。
- `counter`：点赞、收藏、计数和位图状态维护。
- `relation`：关注、取关、粉丝和关注列表，配合 Outbox 事件异步同步。
- `search`：Elasticsearch 索引、搜索、建议和搜索事件处理。
- `llm`：大模型调用、RAG 检索与重排、查询改写、对话记忆、Agent 编排、关系图查询和调试能力。
- `limit`：AI 问答的令牌桶限流。
- `benchmark`：RAG 评测（评估器、装配器、数据模型与内部评测接口）。
- `cache`：本地缓存、Redis 二级缓存和热点 Key 探测。

## 环境要求

- JDK 21
- Maven 3.9+
- Node.js 18+
- MySQL 8
- Redis
- Kafka
- Canal
- Elasticsearch
- MinIO 或其他 S3 兼容对象存储
- Neo4j（可选，用于 RAG 关系图增强）
- 可兼容 OpenAI 协议的聊天模型服务
- 可兼容 OpenAI Embedding 协议的向量模型服务

## 配置说明

后端主配置位于：

```text
zhiguang_be/src/main/resources/application.yml
```

本地运行前需要根据自己的环境配置以下内容：

- MySQL：`spring.datasource.url`、`spring.datasource.username`、`spring.datasource.password`
- Redis：`spring.data.redis.host`、`spring.data.redis.port`、`spring.data.redis.password`
- Kafka：`spring.kafka.bootstrap-servers`
- Canal：`canal.host`、`canal.port`、`canal.destination`、`canal.filter`
- Elasticsearch：`spring.elasticsearch.uris`、账号和密码
- AI Chat：`spring.ai.openai.chat.base-url`、`api-key`、模型名
- AI Embedding：`spring.ai.openai.embedding.base-url`、`api-key`、模型名、向量维度
- 对象存储：`oss.endpoint`、`oss.access-key-id`、`oss.access-key-secret`、`oss.bucket`、`oss.public-domain`
- JWT 密钥：`auth.jwt.private-key`、`auth.jwt.public-key`
- GitHub OAuth：`github.client-id`、`github.client-secret`（回跳地址 `github.redirect-uri`，即 `{前端域名}/callback`）
- 校园登录（可选）：`campus.client-id`、`campus.client-secret`、`campus.redirect-uri`、`campus.token-endpoint`（OIDC）
- 关系图（可选）：`neo4j.uri`、`neo4j.authentication.username`、`neo4j.authentication.password`

- OAuth 与中间件的敏感配置均可通过环境变量覆盖：

| 配置 | 环境变量 |
| --- | --- |
| 对象存储 | `MINIO_ENDPOINT` `MINIO_ACCESS_KEY` `MINIO_SECRET_KEY` `MINIO_BUCKET` `MINIO_PUBLIC_DOMAIN` |
| GitHub OAuth | `GITHUB_CLIENT_ID` `GITHUB_CLIENT_SECRET` `GITHUB_REDIRECT_URI` |
| 校园 OIDC | `CAMPUS_CLIENT_ID` `CAMPUS_CLIENT_SECRET` `CAMPUS_REDIRECT_URI` |
| Neo4j | `NEO4J_URI` `NEO4J_USERNAME` `NEO4J_PASSWORD` |

以对象存储为例（PowerShell）：

```powershell
$env:MINIO_ENDPOINT="http://localhost:9000"
$env:MINIO_ACCESS_KEY="your-access-key"
$env:MINIO_SECRET_KEY="your-secret-key"
$env:MINIO_BUCKET="zhiguang"
$env:MINIO_PUBLIC_DOMAIN="http://localhost:9000"
```

前端接口地址可通过环境变量配置：

```powershell
$env:VITE_API_BASE_URL="http://localhost:8080"
```

开发环境下，如果不配置 `VITE_API_BASE_URL`，Vite 会把 `/api` 代理到 `http://localhost:8080`。

> 注意：不要把真实数据库密码、对象存储密钥、模型 API Key、JWT 私钥提交到公开仓库。建议为团队协作准备 `application-example.yml`，真实配置只保留在本地或部署平台的环境变量中。

## 数据库初始化

后端建表脚本位于：

```text
zhiguang_be/docs/sql/schema.sql
```

首次启动前，在 MySQL 中创建目标数据库后执行该脚本。

示例：

```sql
CREATE DATABASE zhiguang_auth DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

然后导入：

```powershell
mysql -u root -p zhiguang_auth < zhiguang_be/docs/sql/schema.sql
```

增量迁移脚本位于同目录：

- `add_campus_id.sql`：为 `users` 表新增 `campus_id` 并建立唯一索引，启用校园账号登录时需要执行。

## 启动后端

```powershell
cd zhiguang_be
mvn spring-boot:run
```

默认后端服务地址：

```text
http://localhost:8080
```

常用检查：

```powershell
mvn test
mvn -DskipTests package
```

## 启动前端

```powershell
cd zhiguang_fe/zhiguang_fe-main
npm install
npm run dev
```

默认前端开发地址：

```text
http://localhost:5173
```

构建与类型检查：

```powershell
npm run lint
npm run build
```

## 主要接口文档

后端接口文档集中在：

```text
zhiguang_be/docs
```

重点接口文档：

- `API接口.md`：认证、资料、对象存储、知识内容、点赞收藏、关注、搜索等接口。
- `API接口文档_knowpost.md`：知识内容发布流程、Feed、详情、RAG 问答和索引重建接口。
- `API接口文档_用户关系.md`：关注、粉丝、关系状态等接口。
- `API接口文档_计数.md`：点赞、收藏、计数查询接口。

另有面向具体专题的设计文档：

- `计数系统设计方案.md`、`用户关系设计方案.md`：对应模块的设计说明。
- `ARMS监控无数据排查纪实.md`：生产可观测性排查记录。
- `deployment-automation.md`：自动化部署说明。
- `RAG-Prompt-Nacos迁移手册.md`、`RAG多轮记忆改造方案.md`、`RAG-rerank测试报告.md`、`知光知识社区_RAG扩展方案报告.md`、`知光知识社区_全库RAG问答改造计划.md`：RAG 检索链路的设计与测试文档。

前端接口契约另维护在 `zhiguang_fe/zhiguang_fe-main/docs`（认证 / 搜索 / 知文 / 用户关系 / 计数）。

典型接口前缀：

- `/api/v1/auth`
- `/api/v1/profile`
- `/api/v1/storage`
- `/api/v1/knowposts`
- `/api/v1/comments`
- `/api/v1/action`
- `/api/v1/counter`
- `/api/v1/relation`
- `/api/v1/search`
- `/api/internal/rag-benchmark`（评测内部接口）

## 核心流程

### 发布知识内容

1. 前端创建草稿，获得知识内容 ID。
2. 后端生成对象存储预签名上传信息。
3. 前端将 Markdown、图片等资源直传对象存储。
4. 前端回传上传确认信息。
5. 用户补充标题、标签、封面、可见性等元数据。
6. 后端发布内容，并触发搜索索引、Feed、RAG 索引等后续流程。

### RAG 问答

1. 用户围绕某篇知识内容提问。
2. 后端检查并准备该内容的向量索引。
3. 系统进行查询改写、向量检索和上下文组装。
4. 调用大模型生成答案。
5. 前端以流式方式展示回答。

RAG 检索链路采用多阶段处理：

```text
用户问题
-> 问题改写 / standaloneQuestion
-> 原始向量召回 + HyDE 召回
-> RRF 融合候选集
-> NVIDIA rerank 重排
-> questionIntent + sectionType 轻量后处理
-> TopK 上下文交给大模型生成流式答案
```

索引中的 chunk 除正文外还保存 `title`、`sectionTitle`、`sectionType`、
`postId`、`chunkId`、`position` 和 `indexVersion`。重排阶段使用标题、章节和
章节类型增强输入，并根据解释、解决方案、面试和测试等问题意图做轻量分数校正。

在基础 RAG 之上，保留额外的增强与校验路径：

- 对话式问答维护多轮记忆，并支持单篇与全局两种问答范围。
- 查询理解与实体匹配可将问题路由到关系图查询，返回概念间的关联证据。
- Agent 编排层（`AgentPlannerService` `EvidenceCheckService`）负责任务规划、检索与证据校验。
- 可选的 `ExternalKnowledge` 提供者（如 GitHub 官方文档）作为外部权威来源兜底。
- AI 问答使用令牌桶限流，避免单个会话打满模型配额。

正式 Benchmark 位于 `zhiguang_be/scripts/AUTO_Benchwork/`，包含五个互不重复的
T2Retrieval 专题、真实 qrels Gold、检索漏斗报告和答案裁判。传统 BM25/Graph A/B
工具位于 `zhiguang_be/scripts/rag-eval/`，可通过根目录的手动 Workflow 触发。

### 关注关系同步

1. 关注或取关请求在主事务中写入关系表和 Outbox 表。
2. Canal 订阅 MySQL binlog。
3. 事件进入 Kafka。
4. 异步消费者更新粉丝、关注列表、计数和缓存。

## 开发建议

- 修改接口前先查看 `zhiguang_be/docs` 中的契约说明。
- 修改数据表后同步更新 `zhiguang_be/docs/sql/schema.sql`；增量变更以新迁移脚本（如 `add_campus_id.sql`）追加，不覆盖已发布结构。
- 修改运行时配置时，同步更新 `application.yml` 与 `scripts/deploy/sync-nacos-config.sh` 对应的 Nacos 配置。
- 修改前端接口调用时，同步检查 `src/services` 和 `src/types`。
- 修改 RAG 或搜索逻辑时，确认 Elasticsearch 索引名、Embedding 维度和模型配置一致。
- 涉及缓存、Kafka、Canal 的改动，建议同时验证同步链路和失败重试场景。

## 常见问题

### 前端请求后端失败

- 检查后端是否运行在 `localhost:8080`。
- 检查 Vite 代理配置：`zhiguang_fe/zhiguang_fe-main/vite.config.ts`。
- 如果使用跨域地址，设置 `VITE_API_BASE_URL`。

### RAG 问答没有结果

- 检查 Elasticsearch 是否可访问。
- 检查 Embedding 模型维度是否与向量索引维度一致。
- 检查目标知识内容是否已经完成索引。
- 查看后端日志中 `com.tongji` 相关输出。

### 图片或 Markdown 资源无法访问

- 检查对象存储 bucket 是否存在。
- 检查 `oss.public-domain` 是否能被浏览器访问。
- 检查 bucket 访问策略或预签名 URL 是否过期。

## Docker 与自动部署

后端提供 `zhiguang_be/Dockerfile`、`docker-compose.yml` 和 `.env.example`：

```bash
cd zhiguang_be
cp .env.example .env
docker compose up -d --build
docker compose logs -f zhiguang-be
```

真实 `.env`、数据库密码、模型 API Key 和私钥不得提交到 Git。生产环境采用
“内网资源服务器运行后端和中间件、公网轻量服务器提供前端与反向代理”的拓扑，
两台服务器通过 Tailscale 通信；运行时配置由 Nacos 配置中心下发并监听热更新。

GitHub OAuth 与校园 OIDC 的登录回跳依赖一个公网可达的 HTTPS 地址，生产部署需
在对应平台把 `Redirect URL` 指向前端 `{域名}/callback`（校园端为 `/callback/campus`）。

根目录 `.github/workflows/` 是唯一 Workflow 目录：

- `deploy-zhiguang.yml`：推送 `main` 后部署前后端；提交信息包含 `[run-bench]` 时追加五场景 200 题 Benchmark。
- `rag-eval.yml`：手动运行 BM25 A/B 评测。
- `graph-rag-eval.yml`：手动运行 Graph RAG A/B 评测。

部署脚本统一位于 `zhiguang_be/scripts/deploy/`。普通开发流程为：本地修改、提交、
推送 GitHub，然后以 Action 是否成功作为部署结果。

## 项目状态

该项目仍在持续迭代中，重点方向包括：

- 完善敏感配置的环境变量化和示例配置。
- 补充前后端自动化测试。
- 优化 RAG 检索质量、流式问答体验和索引维护流程，深化关系图增强与证据校验。
- 持续扩充 RAG Benchmark 场景和评测工具链。
- 补齐部署脚本与容器化运行文档。
