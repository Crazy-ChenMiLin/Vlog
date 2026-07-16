# RAG rerank 测试报告

测试时间：2026-07-15

## 1. 测试目标

验证 NVIDIA `nvidia/llama-nemotron-rerank-1b-v2` 接入后，RAG 流程是否真正使用 rerank，并观察 rerank 对 `RRF fusedDocs` 排序的影响。

本轮重点不是判断最终答案是否完全正确，而是先确认：

- `rerankedResults` 是否正常返回；
- `answerResults` 是否正常返回；
- `rerankedResults` 是否和 `fusedResults` 有排序差异；
- rerank 是否存在明显误排现象；
- 当前链路耗时是否可接受。

## 2. 测试环境

- 后端地址：`http://localhost:8080`
- 测试接口：`GET /api/v1/knowposts/qa/debug`
- 测试范围：全库 RAG debug
- 测试 topK：`10`
- Rerank 模型：`nvidia/llama-nemotron-rerank-1b-v2`
- Rerank 接口：`/v1/retrieval/nvidia/llama-nemotron-rerank-1b-v2/reranking`
- 测试结果原始文件：`target/rag-rerank-test-report.json`

## 3. 总体结果

| 指标 | 结果 |
|---|---:|
| 测试问题数 | 12 |
| 成功请求数 | 12 |
| 失败请求数 | 0 |
| 返回 `rerankedResults` | 12 |
| 返回 `answerResults` | 12 |
| top1 发生变化 | 12 |
| 整体顺序发生变化 | 12 |
| 平均耗时 | 5.92s |
| 最快耗时 | 3.20s |
| 最慢耗时 | 22.41s |

结论：rerank 已经真实生效。所有测试问题中，`rerankedResults` 都与 `fusedResults` 发生了排序变化。

## 4. 代表案例

### 4.1 RAG：HyDE 与 RRF

问题：

```text
RAG 里面 HyDE 和 RRF 分别解决什么问题？
```

RRF top1：

```text
RAG 观测样例：HyDE 与 RRF 的职责边界 #1
```

Rerank top1：

```text
RAG 观测样例：HyDE 与 RRF 的职责边界 #0
```

观察：

rerank 仍然选择同一篇高相关文章，但把更靠前、更像总览解释的 chunk 提到了第一位。这个结果比较合理。

### 4.2 Java Stream：map、flatMap、collect

问题：

```text
Java Stream 里面 map、flatMap 和 collect 分别适合什么场景？
```

RRF top1：

```text
Java Stream 观测样例：map、flatMap 与 collect #2
```

Rerank top1：

```text
Java Stream 观测样例：map、flatMap 与 collect #0
```

观察：

rerank 把更像定义/总览的 chunk 提到了第一位，排序方向合理。

### 4.3 多轮对话 query rewrite

问题：

```text
多轮对话里为什么要做 query rewrite？
```

RRF top1：

```text
RAG 知识库扩展 036：RAG - 查询改写 #6
```

Rerank top1：

```text
RAG 知识库扩展 036：RAG - 查询改写 #2
```

观察：

rerank 保持在“查询改写”主题内重新排序，说明它没有偏离主题。

### 4.4 Spring 事务失效

问题：

```text
Spring 事务为什么会失效？有哪些常见场景？
```

RRF top1：

```text
RAG 知识库扩展 022：Spring Boot - 事务传播 #5
```

Rerank top1：

```text
RAG 知识库扩展 022：Spring Boot - 事务传播 #1
```

观察：

rerank 仍然选择事务相关文档，并调整到更靠前的正文 chunk，整体可接受。

## 5. 风险案例

### 5.1 chunk 切片问题出现误排

问题：

```text
向量检索为什么要做 chunk 切片？chunk 太大或太小有什么影响？
```

RRF top1：

```text
RAG 知识库扩展 076：RAG - 上下文压缩 #2
```

Rerank top1：

```text
RAG 知识库扩展 097：Java Stream - peek 调试 #1
```

观察：

这个结果明显不理想。问题是 RAG chunk 切片，但 rerank 把 Java Stream 相关 chunk 排到第一，说明当前 rerank 输入存在噪声，或者候选文档质量不够稳定。

### 5.2 Redis 问题存在主题内误排

问题：

```text
Redis 缓存穿透怎么解决？
```

RRF top1：

```text
RAG 知识库扩展 051：Redis - 布隆过滤器 #5
```

Rerank top1：

```text
RAG 知识库扩展 061：Redis - 分布式锁 #1
```

观察：

rerank 没有跑出 Redis 大主题，但把“分布式锁”排到了“缓存穿透”前面，不如 RRF 的“布隆过滤器”贴题。

## 6. 结论

功能结论：

```text
rerank 已经接入成功，并且真实参与了排序。
```

效果结论：

```text
rerank 有明显排序能力，但当前还不能直接认为整体效果优于 RRF。
```

主要原因：

- rerank 会重新排序所有 `fusedDocs`，但如果候选里混入噪声，它也可能把噪声排到前面；
- 当前 debug 只能看到最终排序，看不到 rerank 分数 `logit`，不方便解释为什么这么排；
- 传给 rerank 的文本主要是 chunk 正文，没有显式拼接标题、标签等上下文，可能影响模型判断。

## 7. 后续优化建议

优先级建议如下：

1. 在 `RerankService` 传参时拼接标题和正文：

```text
标题：xxx
正文：chunk text
```

2. 在 debug DTO 中增加 rerank 分数：

```text
rerankScore / logit
```

3. 内部召回数量可以大于最终回答数量：

```text
RRF 召回 topK * 2
Rerank 后裁剪 topK
```

4. 建立固定评测集，不只看排序是否变化，还要看“应该命中的文章/主题是否提前”。

5. 对明显跑偏的候选做主题过滤或最低相关性过滤，避免 rerank 在噪声候选里重新洗牌。

## 8. 当前判断

当前阶段可以进入下一步优化：

```text
RerankService 输入文本优化 + debug 增加 rerank 分数
```

不建议立刻继续加 Self-RAG。现在更应该先把 rerank 的观测性补齐，否则后续节点变多后会更难判断是哪一步影响了最终效果。
