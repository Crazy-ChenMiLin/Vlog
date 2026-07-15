# 知光知识社区

知光是一个面向知识内容发布、学习交流和智能问答的社区项目。项目采用前后端分离架构，后端负责认证、内容、关系链、计数、搜索、对象存储和 RAG 问答能力，前端提供知识流、内容详情、发布编辑、个人主页、搜索和 AI 对话等页面。

当前仓库是前后端合仓：

- 后端：`zhiguang_be`，Java 21 + Spring Boot 3。
- 前端：`zhiguang_fe/zhiguang_fe-main`，React + Vite + TypeScript。

## 功能概览

- 用户认证：基于 Spring Security 的 JWT 认证，支持访问令牌和刷新令牌。
- 知识发布：支持草稿创建、内容上传确认、元数据编辑、发布、置顶、可见性控制和删除。
- 对象存储：通过后端生成预签名信息，前端直传内容、图片等资源到对象存储。
- 首页 Feed：面向知识内容列表展示，结合本地缓存、Redis 缓存和热点探测优化读取。
- 点赞收藏：支持点赞、取消点赞、收藏、取消收藏和计数查询。
- 用户关系：支持关注、取关、粉丝数、关注数以及关系状态维护。
- 搜索系统：基于 Elasticsearch 实现内容检索、标签过滤和搜索建议。
- AI 能力：支持知识文章摘要生成、单篇知识内容 RAG 问答、流式输出和向量索引重建。
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
- MinIO / S3 兼容对象存储
- Maven

### 前端

- React 18
- TypeScript
- Vite 5
- React Router
- React Markdown
- CSS Modules

## 目录结构

```text
.
├── zhiguang_be
│   ├── db                 # MySQL 建表脚本
│   ├── docs               # 后端接口文档
│   ├── scripts            # 辅助脚本
│   ├── src/main/java      # 后端业务代码
│   ├── src/main/resources # MyBatis mapper、密钥、配置等资源
│   └── pom.xml
├── zhiguang_fe
│   └── zhiguang_fe-main
│       ├── docs
│       ├── public
│       ├── src            # 前端页面、组件、服务和类型定义
│       └── package.json
└── README.md
```

## 后端模块

- `auth`：登录、注册、验证码、JWT 签发、刷新令牌和登录日志。
- `profile`：用户资料、头像、个人主页信息。
- `knowpost`：知识内容草稿、发布、详情、列表、摘要生成和 RAG 入口。
- `storage`：对象存储预签名上传、公开访问地址生成。
- `counter`：点赞、收藏、计数和位图状态维护。
- `relation`：关注、取关、粉丝和关注列表，配合 Outbox 事件异步同步。
- `search`：Elasticsearch 索引、搜索、建议和搜索事件处理。
- `llm`：大模型调用、RAG 检索、对话记忆、查询改写和调试能力。
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

对象存储配置已支持环境变量覆盖：

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
zhiguang_be/db/schema.sql
```

首次启动前，在 MySQL 中创建目标数据库后执行该脚本。

示例：

```sql
CREATE DATABASE zhiguang_auth DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

然后导入：

```powershell
mysql -u root -p zhiguang_auth < zhiguang_be/db/schema.sql
```

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

重点文档：

- `API接口.md`：认证、资料、对象存储、知识内容、点赞收藏、关注、搜索等接口。
- `API接口文档_knowpost.md`：知识内容发布流程、Feed、详情、RAG 问答和索引重建接口。
- `docs-user`：用户资料、关注关系等补充说明。

典型接口前缀：

- `/api/v1/auth`
- `/api/v1/profile`
- `/api/v1/storage`
- `/api/v1/knowposts`
- `/api/v1/action`
- `/api/v1/counter`
- `/api/v1/relation`
- `/api/v1/search`

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

### 关注关系同步

1. 关注或取关请求在主事务中写入关系表和 Outbox 表。
2. Canal 订阅 MySQL binlog。
3. 事件进入 Kafka。
4. 异步消费者更新粉丝、关注列表、计数和缓存。

## 开发建议

- 修改接口前先查看 `zhiguang_be/docs` 中的契约说明。
- 修改数据表后同步更新 `zhiguang_be/db/schema.sql`。
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

## 项目状态

该项目仍在持续迭代中，重点方向包括：

- 完善敏感配置的环境变量化和示例配置。
- 补充前后端自动化测试。
- 优化 RAG 检索质量、流式问答体验和索引维护流程。
- 补齐部署脚本与容器化运行文档。
