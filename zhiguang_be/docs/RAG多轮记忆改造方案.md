# RAG 多轮记忆改造方案

## 1. 目标

在现有 RAG 流程基础上加入“会话记忆 + 查询改写”，让用户可以连续追问，并且支持跨设备继续同一个会话。

当前流程：

```text
question
  -> HyDE
  -> 原问题检索 + HyDE 检索
  -> RRF 融合
  -> GPT 生成回答
```

改造后流程：

```text
originalQuestion + 最近 6 条 history
  -> Query Rewrite
  -> standaloneQuestion
  -> HyDE(standaloneQuestion)
  -> standaloneQuestion 检索 + HyDE_standaloneQuestion 检索
  -> RRF 融合 fusedDocs
  -> GPT 基于 history + originalQuestion + standaloneQuestion + fusedDocs 回答
  -> 保存 user / assistant 消息
```

核心原则：

```text
真实问题负责对话体验
standaloneQuestion 负责检索
HyDE_standaloneQuestion 负责增强召回
fusedDocs 负责最终知识上下文
```

## 2. 会话与权限设计

Memory 版接口必须登录，因为会话必须和用户绑定。

会话区分两种 scope：

```text
global：全库问答
post：单篇文章问答
```

每个 conversation 记录包含：

```json
{
  "id": "335888888888888888",
  "userId": "123",
  "scope": "global",
  "postId": null,
  "title": "全库问答",
  "createdAt": "...",
  "updatedAt": "..."
}
```

单篇文章对话：

```json
{
  "id": "335999999999999999",
  "userId": "123",
  "scope": "post",
  "postId": "335239846949949440",
  "title": "Redis 缓存观测样例",
  "createdAt": "...",
  "updatedAt": "..."
}
```

安全规则：

```text
conversationId 为空：后端自动创建新会话
conversationId 存在且属于当前 userId：继续使用
conversationId 存在但不属于当前 userId：拒绝，403
conversationId 存在但查不到：拒绝，404
scope/postId 与会话记录不一致：拒绝
```

注意：权限校验必须看数据库里的 `user_id`，不能只依赖前端传参。

## 3. 数据库存储

第一版直接落到 MySQL，不使用 JSONL 文件。

原因：

```text
需要登录态 userId 隔离
需要跨设备继续会话
需要多会话列表
需要 global/post scope 隔离
后续可能增加删除、分页、标题、搜索
```

两张表即可：

```text
rag_conversation：会话元数据
rag_message：会话消息
```

### 3.1 rag_conversation

字段：

```text
id：会话 ID，雪花 ID
user_id：会话归属用户
scope：global / post
post_id：scope=post 时绑定文章，scope=global 时为空
title：会话标题，第一版可以使用默认标题
created_at：创建时间
updated_at：最后更新时间
```

### 3.2 rag_message

字段：

```text
id：消息 ID，雪花 ID
conversation_id：所属会话
user_id：消息归属用户，用于查询和安全兜底
role：user / assistant
content：消息内容
created_at：创建时间
```

第一版不加 `token_count`。这里避免和模型接口里的 `max_tokens` / `max_completion_tokens` 混淆；之前中转站不支持的是模型请求参数，不是数据库统计字段。

读取策略：

```text
Query Rewrite 读取最近 6 条 message
Final Answer 第一版也带最近 6 条 message
```

这里的 6 条是 6 条 message，不是 6 轮对话，大约等于最近 3 轮。

## 4. 新增与改造的 Service

### 4.1 IdService

包建议：

```text
com.tongji.common.id
```

职责：

```text
nextId()
nextIdString()
```

项目已有 `com.tongji.knowpost.id.SnowflakeIdGenerator`，后续可以抽到 `common.id.IdService`，供 RAG 会话和其他业务统一使用。

### 4.2 RagConversationMemoryService

职责：

```text
createConversation(userId, scope, postId)
validateConversation(userId, conversationId, scope, postId)
loadRecentMessages(userId, conversationId, limit)
appendMessage(userId, conversationId, role, content)
touchConversation(conversationId)
```

注意：

```text
它负责 DB 读写
它负责会话归属和 scope/postId 校验
它不负责检索
它不负责调用大模型
```

### 4.3 QueryRewriteService

职责：

```text
rewrite(originalQuestion, recentMessages)
```

输入：

```text
originalQuestion：用户真实问题
recentMessages：最近 6 条会话历史
```

输出：

```text
standaloneQuestion
```

失败降级：

```text
模型超时 / 报错 / 返回空字符串时，直接返回 originalQuestion
```

示例：

```text
history:
用户：Redis 缓存是什么？
助手：Redis 缓存是...

originalQuestion:
那击穿呢？

standaloneQuestion:
Redis 缓存击穿是什么？
```

### 4.4 RagQueryService

这是主要编排层，需要改造。

新增 memory 版方法，示例：

```text
streamChatAnswerFlux(userId, conversationId, scope, postId, originalQuestion, topK)
```

职责：

```text
1. 根据 conversationId 创建或校验会话
2. 读取最近 6 条 history
3. 调 QueryRewriteService 生成 standaloneQuestion
4. 调 RagRetrievalService 检索
5. 调 GPT 生成最终回答
6. 流式返回给前端
7. 回答完成后保存 user message 和 assistant message
```

注意：`RagRetrievalService` 不应该注入 memory，也不应该知道 DB 表。

### 4.5 RagRetrievalService

保持检索层干净。

语义调整：

```text
入参 question 实际传入 standaloneQuestion
HyDE 基于 standaloneQuestion 生成 HyDE_standaloneQuestion
```

内部逻辑：

```text
standaloneQuestion -> standaloneQuestionDocs
HyDE_standaloneQuestion -> HyDE_standaloneQuestionDocs
standaloneQuestionDocs + HyDE_standaloneQuestionDocs -> RRF -> fusedDocs
```

可以考虑把变量名从：

```text
originalDocs
hydeDocs
```

逐步调整为：

```text
standaloneQuestionDocs
hydeStandaloneQuestionDocs
```

DTO 字段是否重命名可以单独评估，避免一次改动太大。

## 5. Controller / 接口设计

新增 memory 版流式接口：

```text
POST /api/v1/knowposts/qa/chat/stream
```

请求体：

```json
{
  "conversationId": null,
  "scope": "global",
  "postId": null,
  "question": "Redis 缓存是什么？",
  "topK": 5
}
```

单篇文章：

```json
{
  "conversationId": null,
  "scope": "post",
  "postId": "335239846949949440",
  "question": "这一段是什么意思？",
  "topK": 5
}
```

SSE 响应建议：

```text
event: meta
data: {"conversationId":"335888888888888888"}

event: message
data: Redis

event: message
data: 缓存

event: done
data: {}
```

前端收到 `meta` 后保存当前 `conversationId`，后续追问带上它。

旧接口保留：

```text
GET /api/v1/knowposts/qa/stream
GET /api/v1/knowposts/{id}/qa/stream
```

旧接口继续匿名可用，不带 memory。

新 memory 版接口必须登录。

## 6. Final Answer Prompt 输入

检索阶段只使用：

```text
standaloneQuestion
HyDE_standaloneQuestion
```

最终回答阶段使用：

```text
recentMessages
originalQuestion
standaloneQuestion
fusedDocs
```

原因：

```text
检索要干净
回答要有对话上下文
```

Prompt 需要明确约束：

```text
对话历史只用于理解用户当前问题。
standaloneQuestion 只表示系统对当前问题的理解。
最终答案必须基于知识库上下文 fusedDocs。
如果上下文不足，说明不确定。
```

## 7. 保存消息时机

建议：

```text
开始回答前：保存 user message
流式回答完成后：保存完整 assistant message
```

如果中途失败：

```text
第一版只保存 user message
assistant message 不保存
```

后续可以扩展 message 状态：

```text
success
failed
interrupted
```

## 8. 降级策略

Query Rewrite 失败：

```text
standaloneQuestion = originalQuestion
继续 RAG
```

HyDE 失败：

```text
只使用 standaloneQuestionDocs
继续 RAG
```

Conversation 校验失败：

```text
不属于当前用户：403
查不到会话：404
scope/postId 不匹配：拒绝
```

Final Answer 失败：

```text
SSE 返回错误事件或让请求失败
不保存 assistant message
```

## 9. 验收用例

### 9.1 首轮全库问答

输入：

```text
conversationId = null
scope = global
question = Redis 缓存是什么？
```

期望：

```text
后端自动创建 conversation
SSE meta 返回 conversationId
保存 user / assistant 消息
```

### 9.2 追问

输入：

```text
conversationId = 上一轮返回值
question = 那击穿呢？
```

期望：

```text
Query Rewrite 生成：Redis 缓存击穿是什么？
检索命中 Redis 缓存击穿相关切片
回答能承接上一轮
```

### 9.3 越权

输入：

```text
用户 A 携带用户 B 的 conversationId
```

期望：

```text
拒绝，403
不会自动创建新会话
不会读取用户 B 的消息
```

### 9.4 scope 不一致

输入：

```text
global conversationId
请求体 scope = post
```

期望：

```text
拒绝
```

### 9.5 Query Rewrite 失败

模拟：

```text
QueryRewriteService 抛异常或返回空
```

期望：

```text
standaloneQuestion 回退 originalQuestion
RAG 仍然可以回答
```

## 10. 暂不做的内容

第一版暂不做：

```text
会话标题自动总结
会话列表前端 UI
消息删除
消息状态
token_count 统计
rerank 模型
Self-RAG
多源召回
长期 memory 摘要
```

这些可以在 DB 版 memory 稳定后逐步加入。
