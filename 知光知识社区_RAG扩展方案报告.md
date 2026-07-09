# 知光知识社区 RAG 工业级扩展方案报告

| 项目 | 说明 |
|---|---|
| 文档版本 | v1.0 |
| 编制日期 | 2026-07-03 |
| 探索范围 | `D:\resume-project\zhiguang_be`（后端 Spring Boot 项目） |
| 技术基线 | Java 21 + Spring Boot 3.2.4 + Spring AI 1.0.3 + MyBatis + MySQL 9 + Redis(Redisson) + Kafka + Elasticsearch 9.2.1 + 阿里云 OSS + Canal |
| AI 组件 | Spring AI（DashScope text-embedding-v4 / 1536 维 + DeepSeek chat）+ Spring AI ES VectorStore |
| 约束 | 仅 `spring-boot-starter-web`（MVC），无 webflux starter；reactor-core 经 spring-ai 传递引入 |
| 文档性质 | 只读分析报告，不含任何代码改动 |

---

## 目录

1. [现状诊断](#1-现状诊断)
2. [目标架构与映射](#2-目标架构与映射)
3. [阶段依赖与交付路线](#3-阶段依赖与交付路线)
4. [统一包结构建议](#4-统一包结构建议)
5. [Phase1：恢复 API 入口 + 基础治理（重点）](#5-phase1恢复-api-入口--基础治理重点)
6. [Phase2：文档侧入库预处理升级（B1–B5）](#6-phase2文档侧入库预处理升级b1b5)
7. [Phase3：召回层混合检索 + Rerank（C1–C6）](#7-phase3召回层混合检索--rerankc1c6)
8. [Phase4：查询侧优化 + 生成层增强（A1–A4, D1–D2）](#8-phase4查询侧优化--生成层增强a1a4-d1d2)
9. [依赖与风险汇总](#9-依赖与风险汇总)
10. [关键文件路径索引](#10-关键文件路径索引)

---

## 1. 现状诊断

### 1.1 两套 ES 索引（割裂未融合）

| 索引 | 用途 | 管理组件 | 关键字段 |
|---|---|---|---|
| `zhiguang_content_index` | BM25 关键词搜索 | `com.tongji.search` 包 | content_id, title(ik_max_word/ik_smart), body(ik_max_word), description, tags(keyword), author_id, like/favorite/view_count, status, title_suggest(completion) |
| `zhiguang-ai-index` | 向量语义库 | `com.tongji.llm.rag` 包（Spring AI VectorStore） | 1536 维 dense_vector + metadata(postId/chunkId/position/contentEtag/contentSha256/contentUrl/title) |

- BM25 侧由 `SearchIndexInitializer`（@PostConstruct 建 mapping）+ `SearchIndexService`（upsert/softDelete，wait_for 刷新）+ `SearchServiceImpl`（multi_match title^3/body + function_score 互动加权 + tags 过滤 + search_after 游标 + 高亮）管理。
- 向量侧由 Spring AI VectorStore 自动管理（`application.yml`: initialize-schema=true, index-name=zhiguang-ai-index, dimensions=1536）。

### 1.2 现有 RAG 服务（demo 级）

- `RagQueryService.streamAnswerFlux(long postId, String question, int topK, int maxTokens)`：
  - 流程：`ensureIndexed(postId)` → `searchContexts`（按 postId 过滤）→ 拼 `---` 分隔上下文 → 硬编码 system prompt → `ChatClient.stream()` → `Flux<String>`
  - **强绑 postId，仅单篇知文问答**，无全库问答能力
- `RagIndexService.reindexSinglePost`：
  - 流程：查 KnowPostDetailRow → 校验 published+public → 指纹校验(SHA256/ETag, isUpToDate) → RestTemplate 拉 OSS Markdown 正文 → `chunkMarkdown`（标题切段 + 800 字/100 重叠）→ deleteExistingChunks → 组装 Document → `vectorStore.add`
  - **`ensureIndexed` 直接同步调 `reindexSinglePost`，阻塞在问答请求路径**（延迟可达数秒）
- `LlmConfig`：ChatClient bean 绑定 `@Qualifier("deepSeekChatModel")`

### 1.3 被注释的 Controller（API 入口缺失）

| Controller | 路径 | 状态 |
|---|---|---|
| `KnowPostRagController` | `GET /api/v1/knowposts/{id}/qa/stream`（Flux<String> SSE）, `POST /api/v1/knowposts/{id}/rag/reindex` | 整体 `//` 注释 |
| `KnowPostAiController` | `POST /api/v1/knowposts/description/suggest`（AI 摘要） | 整体 `//` 注释 |

- `SecurityConfig` 第 59 行残留放行规则：`.requestMatchers(GET, "/api/v1/knowposts/*/qa/stream").permitAll()`

### 1.4 可复用的基础设施

| 能力 | 现状 | 复用点 |
|---|---|---|
| 鉴权用户提取 | `@AuthenticationPrincipal Jwt jwt`（可空）+ `JwtService.extractUserId(jwt)` | 参考 `SearchController.java:44-45` |
| 限流 | Redisson `getRateLimiter` + `trySetRate(OVERALL, permits, Duration)` + `tryAcquire(1)` | 参考 `CounterServiceImpl.allowedByRateLimiter`（第 383 行） |
| Canal/Kafka outbox | outbox 表 → Canal 订阅 binlog → Kafka `canal-outbox` → 多消费者（search-index-consumer / relation-outbox-consumer），@KafkaListener + 手动 ack | Phase2 异步索引管道参考 |
| 发布流程预索引 | `KnowPostServiceImpl.confirmContent()/publish()` 内 try-catch 同步调 `ragIndexService.ensureIndexed(id)` | Phase2 改异步投递 |
| 线程池 | `ThreadPoolConfig`: taskExecutor (core=10, max=50, queue=200, CallerRunsPolicy) | Phase1 新建 RAG 专用隔离池 |
| 异常体系 | `GlobalExceptionHandler`(@RestControllerAdvice) + `ErrorCode` 枚举 + `BusinessException(ErrorCode, message)` | Phase1 新增 RAG 错误码 |

### 1.5 现状问题清单

1. **API 入口被注释**，RAG 问答与 AI 摘要完全不可用
2. **仅单篇问答**，无跨知文全库问答
3. **SSE 无元数据**（纯 `Flux<String>`，无引用溯源、无错误事件区分）
4. **索引同步阻塞**问答请求路径
5. **无限流**，LLM 易被滥用
6. **无异常降级**，ES/LLM 故障直接 500
7. **无混合检索**（仅向量一路），召回质量有限
8. **无查询改写 / HyDE / Rerank**，工业级 RAG 能力缺失
9. **元数据打标不全**（向量索引缺 tags/visible/creatorId，无法元数据过滤）
10. **分块粗糙**（固定 800 字符，非 token 感知/语义边界）

---

## 2. 目标架构与映射

用户提供的目标架构（mermaid）分 4 层，映射到本项目实际业务（知识社区，元数据用 tags/title/visible/creatorId，而非工业的设备/故障）：

| 目标层 | 节点 | 本项目映射 |
|---|---|---|
| **查询侧（在线）** | A1 查询纠错 / A2 同义词扩展 / A3 元数据过滤提取 / A4 HyDE 假设答案 | LLM 改写 + 提取 tags/visible + 生成假设答案用于向量检索 |
| **文档侧（离线入库）** | B1 清洗 / B2 语义分块 / B3 元数据打标 / B4 向量化 / B5 ES 向量库 | Markdown 去噪 + token 感知分块 + 丰富 metadata + DashScope embedding + zhiguang-ai-index |
| **召回层** | C1 BM25 / C2 向量 / C3 RRF 融合 / C4 粗排 Top20 / C5 Rerank / C6 精排 Top5 | 复用 zhiguang_content_index + zhiguang-ai-index + 应用层 RRF + DashScope gte-rerank |
| **生成层** | D1 prompt 工程化 / D2 结构化答案 + 引用溯源 | 模板化 + few-shot + [知文:postId] 标注 |

**用户决策（已确认）**：
- 元数据映射到本项目实际字段（tags/title/visible/creatorId/type），不新增工业字段
- 扩展为**跨知文全库问答**（去掉 postId 强绑），保留单篇问答作为可选模式
- 优先实施 Phase1

---

## 3. 阶段依赖与交付路线

```
Phase1 (恢复入口+治理)  ← 用户优先
   │  必须先做：为后续阶段提供可运行的 API 入口、限流、降级骨架
   ▼
Phase2 (入库预处理 B1-B5)  ← 依赖 Phase1 的包结构与配置体系
   │  可与 Phase3 并行设计，但 Phase3 的向量检索质量依赖 Phase2 的元数据打标
   ▼
Phase3 (混合检索+Rerank C1-C6)  ← 依赖 Phase2（向量索引元数据补齐）+ Phase1（API 入口）
   ▼
Phase4 (查询侧 A1-A4 + 生成层 D1-D2)  ← 依赖 Phase3（HybridRetriever）+ Phase2（异步管道）
```

- **强依赖顺序**：Phase1 → Phase2 → Phase3 → Phase4
- **可并行**：Phase2 的 B1/B2 清洗分块 与 Phase3 的 RRF/Rerank 算法实现可并行（但集成需 Phase2 先完成）
- **零新增 pom 依赖**：4 个阶段全部复用现有技术栈（reactor-core/Redisson/KafkaTemplate/RestTemplate），DashScope rerank 用 RestTemplate 调 REST。

---

## 4. 统一包结构建议

在 `com.tongji.llm.rag` 下按"在线查询 / 离线入库 / 召回 / 重排 / 生成 / 配置"分层，保持与项目现有 `api → service → impl → mapper → model` 风格一致：

```
com.tongji.llm
├── LlmConfig.java                                    (现有，Phase1 增强: EmbeddingModel 显式配置)
├── service/                                          (现有: KnowPostDescriptionService AI 摘要)
│   └── impl/KnowPostDescriptionServiceImpl.java      (现有)
└── rag/
    ├── RagQueryService.java                          (现有，Phase1 重构为支持全库+单篇双模式)
    ├── api/                                          (Phase1 新建)
    │   ├── RagQaController.java                      (Phase1: 全库问答 + 单篇问答 + reindex)
    │   ├── KnowPostAiController.java                 (Phase1: 从 knowpost/api 迁移恢复，AI 摘要)
    │   └── dto/
    │       ├── RagQaRequest.java                     (全库问答请求体)
    │       ├── SinglePostQaRequest.java              (单篇问答请求体)
    │       ├── RagQaMetaPayload.java                 (SSE meta 事件: 引用列表)
    │       ├── RagReference.java                     (引用项: postId/title/snippet/score)
    │       └── RagErrorPayload.java                  (SSE error 事件)
    ├── query/                                        (Phase4 新建: A1-A4)
    │   ├── QueryRewriteService.java                  (A1 纠错 + A2 同义词)
    │   ├── MetadataExtractor.java                    (A3 元数据过滤提取)
    │   └── HyDEService.java                          (A4 假设答案生成)
    ├── ingest/                                       (Phase2 新建: B1-B5)
    │   ├── RagIndexService.java                      (现有迁移, 重构为编排器)
    │   ├── ContentCleaner.java                       (B1 Markdown 清洗)
    │   ├── SemanticTextSplitter.java                 (B2 语义分块)
    │   ├── MetadataEnricher.java                     (B3 元数据打标)
    │   ├── RagIndexProducer.java                     (Kafka 异步生产者)
    │   └── RagIndexConsumer.java                     (Kafka 异步消费者)
    ├── retrieve/                                     (Phase3 新建: C1-C4)
    │   ├── HybridRetriever.java                      (C1-C4 编排)
    │   ├── BM25Retriever.java                        (C1 复用 zhiguang_content_index)
    │   ├── VectorRetriever.java                      (C2 向量检索)
    │   ├── RrfFusion.java                            (C3 倒数排名融合)
    │   ├── RetrievalResult.java                      (统一召回结果)
    │   └── RetrievalContext.java                     (查询上下文: 重写问题/元数据过滤/HyDE)
    ├── rerank/                                       (Phase3 新建: C5-C6)
    │   ├── RerankService.java                        (接口)
    │   ├── DashScopeRerankService.java               (DashScope rerank 实现)
    │   └── LlmRerankService.java                     (LLM 打分降级实现)
    ├── generate/                                     (Phase4 新建: D1-D2)
    │   ├── RagPromptTemplates.java                   (D1 模板化 prompt)
    │   ├── AnswerGenerator.java                      (D2 结构化生成)
    │   └── CitationFormatter.java                    (D2 引用溯源格式化)
    └── config/                                       (Phase1 新建)
        ├── RagProperties.java                        (RAG 配置: topK/限流/超时等)
        └── RagRateLimiter.java                       (限流组件)
```

**说明**：
- `RagQaController` 放在 `com.tongji.llm.rag.api`，与 `KnowPostRagController`（原 knowpost/api 下）职责区分：RAG 问答是 llm 模块的核心能力，knowpost 模块是宿主。原 `KnowPostRagController` 注释后不再恢复，由 `RagQaController` 统一承接。
- `KnowPostAiController`（AI 摘要）迁移到 `com.tongji.llm.rag.api` 或保留在 knowpost/api。

---

## 5. Phase1：恢复 API 入口 + 基础治理（重点）

### 5.1 目标

1. 取消注释、恢复 RAG 问答与 AI 摘要 API 入口
2. 新增**全库问答**接口（去掉 postId 强绑），保留**单篇问答**接口（向后兼容）
3. SSE 方案定型（Flux vs SseEmitter）
4. 安全治理：放行规则、限流防 LLM 滥用
5. 异常降级：LLM 失败/ES 不可用/空召回的友好提示
6. 索引解耦：缓解 ensureIndexed 同步阻塞（轻量方案，完整异步放 Phase2）
7. 接口契约：DTO + SSE 事件格式（带引用元数据）

### 5.2 SSE 方案决策：保留 Flux<ServerSentEvent<T>>，不用 SseEmitter

**结论**：在 starter-web(MVC) 下，Controller 返回 `Flux<ServerSentEvent<T>>`（produces=text/event_stream）是**稳妥且推荐**的方案，不改用 SseEmitter。

**理由**：
1. **官方支持**：Spring MVC 自 5.0 起通过 `ReactiveTypeHandler` 原生支持返回 Flux/Mono。当 produces=text/event_stream 且 reactor-core 在 classpath（已满足，spring-ai 传递引入），Spring 会自动 subscribe Flux 并通过 `ResponseBodyEmitter` 适配为 SSE 写出。无需 webflux starter。
2. **与 ChatClient.stream() 天然契合**：`ChatClient.stream().content()` 直接返回 `Flux<String>`，用 Flux 透传最自然。改 SseEmitter 需手动 `subscribe` Flux、在 onNext 调 `emitter.send()`、onComplete/onError 调 `emitter.complete()/completeWithError()`、onTimeout/onError 回调里 `cancel subscription`，代码更复杂且易错。
3. **背压**：MVC 下 Flux 无真正背压（servlet push 模型），元素缓冲到 servlet 输出缓冲区。但 SSE 场景 LLM 生成速度（~50 token/s）远低于网络传输，缓冲溢出风险可忽略。
4. **用 ServerSentEvent 而非纯 Flux<String>**：`ServerSentEvent` 可指定 `event`(类型)/`data`/`id`/`retry` 字段，满足"带引用元数据 + 错误事件"需求；纯 `Flux<String>` 只能发 data 事件，无法区分 meta/delta/error。

**必须配套的治理**：
- 全局配置 `spring.mvc.async.request-timeout=-1`（SSE 长连接不超时），否则默认容器超时会截断流式输出。
- Flux 内部用 `onErrorResume` 捕获异常并转 error 事件（**不能依赖 GlobalExceptionHandler**，因为 SSE 响应头已发送，异常处理器无法改写 HTTP 状态码）。
- 用 `doOnCancel` 记录客户端断开日志（ChatClient.stream() 的 Flux 被 cancel 后会中断底层 HTTP 调用，节省 token）。

### 5.3 新增/修改文件清单

#### 5.3.1 新建文件（8 个）

**1. `RagQaController.java`** — 全库问答 + 单篇问答 + reindex 统一入口

```java
package com.tongji.llm.rag.api;

import com.tongji.llm.rag.RagQueryService;
import com.tongji.llm.rag.api.dto.*;
import com.tongji.llm.rag.config.RagRateLimiter;
import com.tongji.auth.token.JwtService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@Validated
@RequiredArgsConstructor
public class RagQaController {
    private final RagQueryService ragQueryService;
    private final RagRateLimiter rateLimiter;
    private final JwtService jwtService;

    /** 全库问答（跨知文），SSE 流式，带引用元数据。
     *  用 GET 而非 POST：前端 EventSource 仅支持 GET，参数走 query string */
    @GetMapping(value = "/api/v1/rag/qa/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> globalQaStream(
            @Valid RagQaRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = (jwt == null) ? null : jwtService.extractUserId(jwt);
        rateLimiter.checkAndAcquire("global", userId, clientIp());
        return ragQueryService.streamGlobalAnswerFlux(req, userId);
    }

    /** 单篇知文问答，保留旧路径，前端零改动 */
    @GetMapping(value = "/api/v1/knowposts/{id}/qa/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> singlePostQaStream(
            @PathVariable("id") long id,
            @RequestParam("question") String question,
            @RequestParam(value = "topK", defaultValue = "5") @Min(1) @Max(20) int topK,
            @RequestParam(value = "maxTokens", defaultValue = "1024") @Min(64) @Max(4096) int maxTokens,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = (jwt == null) ? null : jwtService.extractUserId(jwt);
        rateLimiter.checkAndAcquire("single", userId, clientIp());
        return ragQueryService.streamSinglePostAnswerFlux(id, question, topK, maxTokens, userId);
    }

    /** 手动触发单篇索引重建（需登录） */
    @PostMapping("/api/v1/rag/knowposts/{id}/reindex")
    public int reindex(@PathVariable("id") long id, @AuthenticationPrincipal Jwt jwt) {
        return ragQueryService.reindexSinglePost(id);
    }

    private String clientIp() { /* 从 RequestContextHolder 取 */ }
}
```

**2. `RagQaRequest.java`** — 全库问答请求体（record 风格，跟随项目 DTO 约定）

```java
public record RagQaRequest(
    @NotBlank @Size(max = 500) String question,
    @Min(1) @Max(20) Integer topK,          // 默认 5
    @Min(64) @Max(4096) Integer maxTokens,  // 默认 1024
    List<String> tags,                       // 可选: 元数据过滤
    String visible                           // 可选: 默认 public
) {
    public int topKOrDefault() { return topK == null ? 5 : topK; }
    public int maxTokensOrDefault() { return maxTokens == null ? 1024 : maxTokens; }
    public String visibleOrDefault() { return visible == null ? "public" : visible; }
}
```

**3. `RagQaMetaPayload.java`** — SSE meta 事件载荷（流式开始前发送引用列表）

```java
public record RagQaMetaPayload(
    String query,
    int retrievedCount,
    List<RagReference> references
) {}
```

**4. `RagReference.java`** — 引用项

```java
public record RagReference(
    String postId,
    String title,
    String snippet,   // 命中片段(截断 200 字)
    double score
) {}
```

**5. `RagErrorPayload.java`** — SSE error 事件载荷

```java
public record RagErrorPayload(
    String code,       // RAG_NO_CONTEXT / RAG_LLM_FAILED / RAG_RATE_LIMITED
    String message
) {}
```

**6. `RagProperties.java`** — RAG 配置

```java
@Data
@ConfigurationProperties(prefix = "rag")
public class RagProperties {
    private int defaultTopK = 5;
    private int fetchKMultiplier = 3;
    private int anonymousRatePerMin = 5;
    private int userRatePerMin = 20;
    private Duration rateWindow = Duration.ofMinutes(1);
    private int defaultMaxTokens = 1024;
    private String indexExecutor = "ragIndexExecutor";
    private boolean skipEnsureIndexOnQuery = true; // 全库问答跳过同步索引
}
```

**7. `RagRateLimiter.java`** — 限流组件（复用 Redisson 模式）

```java
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(RagProperties.class)
public class RagRateLimiter {
    private final RedissonClient redisson;
    private final RagProperties props;

    public void checkAndAcquire(String scene, Long userId, String ip) {
        String key;
        int permits;
        if (userId != null) {
            key = "rl:rag:" + scene + ":user:" + userId;
            permits = props.getUserRatePerMin();
        } else {
            key = "rl:rag:" + scene + ":ip:" + (ip == null ? "unknown" : ip);
            permits = props.getAnonymousRatePerMin();
        }
        RRateLimiter limiter = redisson.getRateLimiter(key);
        limiter.trySetRate(RateType.OVERALL, permits, props.getRateWindow());
        if (!limiter.tryAcquire(1)) {
            throw new BusinessException(ErrorCode.RAG_RATE_LIMITED, "提问过于频繁，请稍后再试");
        }
    }
}
```

**8. `RagIndexExecutorConfig.java`** — RAG 索引专用线程池（与业务隔离）

```java
@Configuration
public class RagIndexExecutorConfig {
    @Bean(name = "ragIndexExecutor")
    public ThreadPoolTaskExecutor ragIndexExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);      // 索引重 IO，小核心
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("RagIndex-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
```

#### 5.3.2 修改文件（6 个）

**1. `RagQueryService.java`（重构）** — 核心改动：
- 新增 `streamGlobalAnswerFlux(RagQaRequest, Long userId)`：全库向量检索（不按 postId 过滤），支持 tags/visible 元数据过滤
- 重命名/保留 `streamSinglePostAnswerFlux`：单篇问答（向后兼容）
- 索引解耦：全库问答跳过 ensureIndexed；单篇问答用 `CompletableFuture.runAsync(..., ragIndexExecutor)` 异步提交
- 统一返回 `Flux<ServerSentEvent<Object>>`，带 meta/delta/done/error 事件
- 异常降级：`onErrorResume` 转 error 事件；空召回返回友好提示

关键骨架：

```java
public Flux<ServerSentEvent<Object>> streamGlobalAnswerFlux(RagQaRequest req, Long userId) {
    int topK = req.topKOrDefault();
    int fetchK = Math.max(topK * props.getFetchKMultiplier(), 20);
    List<RagReference> refs = new ArrayList<>();

    return Mono.fromCallable(() -> searchGlobalContexts(req, fetchK, topK, refs))
        .flatMapMany(contexts -> {
            if (contexts.isEmpty()) {
                return Flux.just(errorEvent("RAG_NO_CONTEXT", "未找到相关知文，换个问题试试吧"));
            }
            RagQaMetaPayload meta = new RagQaMetaPayload(req.question(), refs.size(), refs);
            String context = String.join("\n\n---\n\n", contexts);
            String system = "你是中文知识助手。只能依据提供的知文上下文回答；"
                + "引用知文时以 [知文:postId] 标注；无法确定的请说明不确定。";
            String user = "问题：" + req.question() + "\n\n上下文：\n" + context + "\n\n请基于上下文作答。";
            Flux<ServerSentEvent<Object>> metaFlux = Flux.just(sseEvent("meta", meta));
            Flux<ServerSentEvent<Object>> deltaFlux = chatClient.prompt().system(system).user(user)
                .options(DeepSeekChatOptions.builder().model("deepseek-chat")
                    .temperature(0.2).maxTokens(req.maxTokensOrDefault()).build())
                .stream().content()
                .map(chunk -> sseEvent("delta", chunk))
                .onErrorResume(e -> Flux.just(errorEvent("RAG_LLM_FAILED", "生成回答时出错，请重试")));
            return metaFlux.concatWith(deltaFlux).concatWith(Flux.just(sseEvent("done", "{}")));
        })
        .doOnCancel(() -> log.info("Global QA stream cancelled, q={}", req.question()));
}

private List<String> searchGlobalContexts(RagQaRequest req, int fetchK, int topK, List<RagReference> refs) {
    SearchRequest.Builder sb = SearchRequest.builder().query(req.question()).topK(fetchK);
    Filter.Expression filter = new Filter.Expression(
        new Filter.Key("visible"), Filter.Expression.Op.EQ,
        new Filter.Value(req.visibleOrDefault()));
    sb.filterExpression(filter);
    try {
        List<Document> docs = vectorStore.similaritySearch(sb.build());
        List<String> out = new ArrayList<>(topK);
        for (Document d : docs) {
            if (out.size() >= topK) break;
            if (d.getText() == null || d.getText().isEmpty()) continue;
            out.add(d.getText());
            refs.add(toReference(d));
        }
        return out;
    } catch (Exception e) {
        log.error("Vector search failed: {}", e.getMessage());
        return Collections.emptyList(); // 降级: 空召回
    }
}
```

**2. `KnowPostAiController.java`** — 取消 `//` 注释，恢复 AI 摘要（`POST /api/v1/knowposts/description/suggest`），鉴权默认 authenticated。

**3. `SecurityConfig.java`** — 放行规则：
```java
// RAG 问答: 全库(GET) + 单篇(GET, 旧路径兼容), 公开访问(限流防滥用)
.requestMatchers(HttpMethod.GET, "/api/v1/rag/qa/stream").permitAll()
.requestMatchers(HttpMethod.GET, "/api/v1/knowposts/*/qa/stream").permitAll()
// reindex 需登录(后续可加 @PreAuthorize 限制管理员)
.requestMatchers(HttpMethod.POST, "/api/v1/rag/knowposts/*/reindex").authenticated()
```

**4. `ErrorCode.java`** — 新增 RAG 错误码：
```java
RAG_NO_CONTEXT("RAG_NO_CONTEXT", "未找到相关知文"),
RAG_LLM_FAILED("RAG_LLM_FAILED", "生成回答失败"),
RAG_RATE_LIMITED("RAG_RATE_LIMITED", "提问过于频繁"),
RAG_INDEX_FAILED("RAG_INDEX_FAILED", "索引构建失败"),
```

**5. `application.yml`** — SSE 超时 + RAG 配置：
```yaml
spring:
  mvc:
    async:
      request-timeout: -1  # SSE 长连接不超时(关键!)
rag:
  default-top-k: 5
  fetch-k-multiplier: 3
  anonymous-rate-per-min: 5
  user-rate-per-min: 20
  rate-window: 1m
  default-max-tokens: 1024
  index-executor: ragIndexExecutor
  skip-ensure-index-on-query: true
```

**6. `KnowPostRagController.java`** — 保持注释（废弃），单篇问答由 `RagQaController` 以相同路径 `/api/v1/knowposts/{id}/qa/stream` 承接，前端零改动。

### 5.4 接口契约（SSE 事件格式）

**全库问答** `GET /api/v1/rag/qa/stream?question=...&topK=5&maxTokens=1024&tags=java,编程&visible=public`

> 用 GET 而非 POST：前端 `new EventSource(url)` 仅支持 GET。question 走 query string，tags 用逗号分隔或多值 `?tags=java&tags=编程`。

响应（SSE，Content-Type: text/event-stream）：
```
event: meta
data: {"query":"Java 的 HashMap 原理?","retrievedCount":3,"references":[{"postId":"123","title":"HashMap 源码","snippet":"...","score":0.0}]}

event: delta
data: "HashMap 基于"

event: delta
data: "哈希表实现"

event: done
data: "{}"
```

错误事件：
```
event: error
data: {"code":"RAG_NO_CONTEXT","message":"未找到相关知文，换个问题试试吧"}
```

### 5.5 索引解耦方案（Phase1 轻量版）

| 路径 | 策略 |
|---|---|
| 全库问答 | 完全跳过 `ensureIndexed`（`skipEnsureIndexOnQuery=true`）。新鲜度依赖发布流程已有的同步预索引 + Phase2 的 Kafka 异步索引 |
| 单篇问答 | `CompletableFuture.runAsync(() -> ensureIndexed, ragIndexExecutor)` 异步提交，**不等待**直接检索。首次可能空召回，返回 RAG_NO_CONTEXT，用户重试可接受 |
| 线程池 | `ragIndexExecutor`（core=2, max=4）与业务 `taskExecutor` 隔离 |

### 5.6 异常降级策略

| 故障场景 | 降级行为 | 实现 |
|---|---|---|
| LLM 调用失败 | 返回 error 事件 `RAG_LLM_FAILED` | `onErrorResume` 在 Flux 内捕获 |
| ES 向量库不可用 | 返回 error 事件 `RAG_NO_CONTEXT` | `searchGlobalContexts` try-catch 返回空 list |
| 空召回 | 返回 error 事件 `RAG_NO_CONTEXT` + 友好文案 | 检查 contexts.isEmpty() |
| 客户端断开 | 中断 LLM 流，记录日志 | `doOnCancel` |
| 限流触发 | 抛 BusinessException → GlobalExceptionHandler 返回 429/400 | `RagRateLimiter` 在进入 Flux 前同步抛出 |
| 参数校验失败 | GlobalExceptionHandler 返回 400 | @Valid + record 校验注解 |

### 5.7 风险点

1. **MVC 下 Flux 的线程模型**：Spring MVC 用 SimpleAsyncTaskExecutor 处理 reactive 返回值，高并发下线程开销大。缓解：可配置 `WebMvcConfigurer` 的 async executor，Phase1 先用默认，Phase3 视压测优化。
2. **ServerSentEvent 序列化**：data 为 String 时直接输出，为 record/对象时用 Jackson 序列化为 JSON。需确认前端 EventSource 的 data 解析策略。
3. **限流 key 用 IP**：需从 `RequestContextHolder`/`X-Forwarded-For` 取真实 IP，反向代理场景需处理。匿名 IP 限流易被 NAT 绕过，Phase1 够用。
4. **前端兼容性（已处理）**：单篇问答保留旧路径，前端 `CourseDetailPage.tsx` 零改动；全库问答是新增接口，前端需新增 EventSource 调用（仅支持 GET）。

### 5.8 验收标准

- [ ] `mvn compile` 通过，无编译错误
- [ ] `GET /api/v1/rag/qa/stream` 返回 SSE，含 meta/delta/done 事件，前端 EventSource 可接收
- [ ] `GET /api/v1/knowposts/{id}/qa/stream` 单篇问答可用
- [ ] `POST /api/v1/knowposts/description/suggest` AI 摘要可用
- [ ] 匿名用户可调用问答（permitAll 生效），reindex 需登录
- [ ] 连续提问超过限流阈值（匿名 5 次/分钟）返回 RAG_RATE_LIMITED
- [ ] 关闭 ES 后调用问答，返回 RAG_NO_CONTEXT 而非 500
- [ ] 关闭 DeepSeek 后调用问答，返回 RAG_LLM_FAILED 而非 500
- [ ] SSE 连接持续 2 分钟不超时（验证 request-timeout=-1 生效）
- [ ] 单篇问答的 ensureIndexed 异步执行，请求不阻塞（首字延迟 < 1s）

---

## 6. Phase2：文档侧入库预处理升级（B1–B5）

### 6.1 目标

1. B1 Markdown 清洗去噪
2. B2 语义分块（token 感知/语义边界）
3. B3 元数据打标（tags/title/visible/creatorId/type/publish_time）
4. B4 显式 EmbeddingModel 配置 + 批量 embedding
5. B5 评估索引合并策略
6. 异步索引管道（Kafka + Canal outbox）

### 6.2 新增文件（5 个）

**1. `ContentCleaner.java`（B1）** — Markdown 清洗：去 HTML 标签残留、统一空白、去水印行。

**2. `SemanticTextSplitter.java`（B2）** — 语义分块：标题边界优先 + token 感知长度（~512 token）+ 重叠（~64 token）。推荐自实现字符估算版本（中文 1 字≈1.5 token，英文 1 词≈1.3 token），零依赖，精度够用；Phase4 视效果再换 Spring AI `TokenTextSplitter`（需 tokenizers-jni，体积大）。

**3. `MetadataEnricher.java`（B3）** — 元数据打标，丰富 Document metadata：

| metadata 字段 | 来源 |
|---|---|
| postId / chunkId / position | 现有保留 |
| title | row.getTitle() |
| tags | 解析 row.getTags() JSON → List<String> |
| visible | row.getVisible() |
| creatorId | row.getCreatorId() |
| type | row.getType() |
| contentEtag / contentSha256 / contentUrl | 现有保留 |
| publishTime | row.getPublishTime() |

**4. `RagIndexProducer.java`** — Kafka 异步索引生产者，发布任务到 `rag-index` topic（用 postId 作 partition key 保序）。

**5. `RagIndexConsumer.java`** — Kafka 异步消费者，@KafkaListener(groupId=rag-index-consumer) + 手动 ack，解析 payload(postId/op)，调 `indexService.reindexSinglePost` 或 `deletePost`。

### 6.3 修改文件

- `RagIndexService.java`（迁移到 ingest 子包，重构为编排器）：注入 ContentCleaner / SemanticTextSplitter / MetadataEnricher，流程改为 clean → split → enrich → add；新增 `deletePost(long postId)`；ensureIndexed 改为投递 Kafka 消息
- `LlmConfig.java`（B4）：确认 EmbeddingModel bean 可注入，批量 embedding 用 `embeddingModel.embed(List<String>)`
- `KnowPostServiceImpl.java`：confirmContent()/publish() 的 `ensureIndexed(id)` 改为 `ragIndexProducer.publishIndexTask(id, "upsert")`；delete() 新增 `publishIndexTask(id, "delete")`

### 6.4 B5 索引合并策略评估

**结论：Phase2 不合并两套索引，保持 `zhiguang_content_index`(BM25) + `zhiguang-ai-index`(向量) 分离**。

理由：
1. 合并需在 `zhiguang-ai-index` 补齐 title/body 的 IK 分词 text 字段 + 互动计数字段，破坏 Spring AI VectorStore 的自动 mapping 管理（initialize-schema=true 会冲突）
2. 两套索引由不同组件管理（SearchIndexService vs VectorStore），耦合后维护成本高
3. Phase3 的 HybridRetriever 通过 RRF 在应用层融合双路结果，无需索引层合并
4. Phase2 在向量索引补齐元数据即可（MetadataEnricher 已做），支持向量检索的元数据过滤

### 6.5 风险点

1. Kafka 消费顺序：同一 postId 多次变更需保序（用 postId 做 partition key，Producer 已用）
2. 消费失败重试：当前 catch-all + ack，丢消息风险。Phase2 可引入死信队列（Phase4 优化）
3. 异步索引延迟：发布后到可被检索有秒级延迟，可接受

### 6.6 验收标准

- [ ] 发布知文后，Kafka rag-index topic 收到消息，向量索引异步写入
- [ ] 向量索引 metadata 含 tags/visible/creatorId/type/publishTime
- [ ] 分块为 token 感知（单块 ~512 token），重叠 ~64 token
- [ ] Markdown HTML 标签残留被清洗
- [ ] 删除知文后，向量索引对应切片被删除
- [ ] 问答请求路径不再同步调 ensureIndexed

---

## 7. Phase3：召回层混合检索 + Rerank（C1–C6）

### 7.1 目标

1. C1 BM25 检索（复用 zhiguang_content_index）
2. C2 向量检索（基于扩展问题，Phase4 才有 HyDE）
3. C3 RRF 倒数排名融合
4. C4 粗排 Top20
5. C5 Rerank 精排（DashScope rerank）
6. C6 精排 Top5

### 7.2 新增文件（8 个）

**1. `RetrievalResult.java`** — 统一召回结果 record：postId/title/snippet/text/bm25Score/vectorScore/fusedScore/rerankScore/metadata

**2. `RetrievalContext.java`** — 查询上下文 record：originalQuery/expandedQuery/hydeQueryOrOriginal/tags/visible/topK

**3. `BM25Retriever.java`（C1）** — 复用 `zhiguang_content_index` 的 multi_match(title^3, body) + status=published 过滤。**注意：此处不叠加 function_score（互动加权），保持纯相关性供 RRF 融合**。返回 content_id/title/body 片段/score。

**4. `VectorRetriever.java`（C2）** — 向量语义检索，基于扩展问题（Phase4 用 HyDE 替换 query），支持 tags/visible 元数据过滤。

**5. `RrfFusion.java`（C3）** — Reciprocal Rank Fusion 实现：

```
RRF 公式: score(d) = Σ 1 / (K + rank_i(d))   // K=60 经验常数
```

按 postId 聚合（同篇多切片取最高排名），融合后按 fusedScore 降序。

**6. `HybridRetriever.java`（C1-C4 编排）** — BM25 + Vector 双路宽召回（每路 max(topK*4, 20)）→ RRF 融合 → 粗排 Top20。

**7. `RerankService.java` + `DashScopeRerankService.java`（C5 推荐）** — DashScope gte-rerank REST API 实现：组装 query + documents → POST → 拿回 relevance_score → 降序取 topN → 回填 rerankScore。异常时降级为不精排。

**8. `LlmRerankService.java`（C5 降级）** — 用 DeepSeek 对候选打分（0-10），成本高，仅 DashScope 不可用时使用。

### 7.3 修改文件

- `RagQueryService.java`：`streamGlobalAnswerFlux` 的检索阶段从 `searchGlobalContexts` 改为调用 `HybridRetriever.retrieve()` + `RerankService.rerank()`，取 Top5 作为上下文。

### 7.4 Rerank 选型推荐

| 方案 | 优点 | 缺点 | 推荐度 |
|---|---|---|---|
| **DashScope gte-rerank** | 阿里云托管，与现有 embedding 同厂商，低延迟，专用模型效果好 | 需调 REST API（无 Spring AI 封装） | **推荐（Phase3 主用）** |
| bge-reranker 本地部署 | 无 API 成本，数据不出境 | 需部署推理服务，占用 GPU/内存 | 备选（数据敏感场景） |
| LLM 打分（DeepSeek） | 零额外依赖，可解释 | 成本高，延迟高，排序稳定性差 | 降级方案 |

### 7.5 风险点

1. BM25 与向量检索结果去重：按 postId 聚合时，同篇多切片如何选取代表（当前取最高排名，可优化为拼接 Top 切片）
2. RRF 常数 K=60 是经验值，可按效果调参
3. DashScope rerank API 需确认鉴权方式（ApiKey 复用 application.yml 的 openai.api-key）

### 7.6 验收标准

- [ ] HybridRetriever 返回 BM25+向量融合后的 Top20
- [ ] RerankService 返回精排 Top5
- [ ] 全库问答的召回质量优于纯向量检索（人工评测）
- [ ] DashScope rerank 失败时降级为不精排，不报错

---

## 8. Phase4：查询侧优化 + 生成层增强（A1–A4, D1–D2）

### 8.1 目标

1. A1 查询纠错
2. A2 同义词扩展
3. A3 元数据提取（tags/visible）
4. A4 HyDE 假设答案生成
5. D1 prompt 工程化（模板+few-shot+约束）
6. D2 引用溯源（结构化标注 [知文:postId]）

### 8.2 新增文件（6 个）

**1. `QueryRewriteService.java`（A1+A2）** — 用 LLM 做轻量查询改写（纠错 + 同义表述），temperature=0.0, maxTokens=100，失败降级返回原查询。

**2. `MetadataExtractor.java`（A3）** — 规则优先（扫描问题中的已知 tag 词典）+ LLM 兜底（输出 {"tags":[...],"visible":"public"} JSON），失败返回空 tags + visible=public。

**3. `HyDEService.java`（A4）** — 让 LLM 生成假设性答案（2-3 句，temperature=0.3, maxTokens=150），用其 embedding 做向量检索提升语义匹配。失败返回 null，调用方降级为原查询。

**4. `RagPromptTemplates.java`（D1）** — prompt 工程化：

```
SYSTEM = """
你是中文知识社区助手。请严格依据提供的知文上下文回答用户问题。
约束:
1. 只能依据上下文, 不得编造;
2. 引用知文时标注 [知文:postId], 多处引用分别标注;
3. 上下文不足时明确说明"根据现有知文无法确定";
4. 答案结构化, 分点陈述, 简洁清晰。
"""
FEW_SHOT = """示例: [知文:101]... [知文:102]... 问题:... 回答:..."""
```

**5. `CitationFormatter.java`（D2）** — 把召回结果格式化为带 `[知文:postId]` 标注的上下文。

**6. `AnswerGenerator.java`（D2）** — 结构化生成编排。

### 8.3 修改文件

- `RagQueryService.java`：`streamGlobalAnswerFlux` 增加查询侧流水线：

```
原问题 → QueryRewriteService.rewrite() → MetadataExtractor.extract()
      → HyDEService.generateHypotheticalAnswer()
      → 构建 RetrievalContext(original, expanded, hyde, tags, visible, topK)
      → HybridRetriever.retrieve() → RerankService.rerank() → Top5
      → CitationFormatter.formatContext() → RagPromptTemplates.buildUser()
      → ChatClient.stream()
```

### 8.4 A1 纠错方案选择

- **Phase4 主用 LLM 纠错**（QueryRewriteService，低成本，DeepSeek 调用 ~100 token）
- 备选：规则字典（维护常见错别字表），适合高频固定错误，作为 LLM 的前置加速

### 8.5 风险点

1. 查询改写增加延迟（3 次 LLM 调用：rewrite + extract + HyDE）。**缓解**：并行化（用 CompletableFuture 并行），或对简单问题跳过 HyDE
2. HyDE 可能引入偏差（假设答案错误导致向量检索偏移）。**缓解**：HyDE 与原查询双路向量检索，RRF 融合
3. 引用标注依赖 LLM 遵循 prompt 约束，需 few-shot 强化

### 8.6 验收标准

- [ ] 查询改写后，召回相关性提升（人工评测）
- [ ] 答案中包含 [知文:postId] 引用标注
- [ ] HyDE 失败时降级为原查询，不报错
- [ ] 查询侧总延迟 < 3s（并行化后）

---

## 9. 依赖与风险汇总

### 9.1 pom.xml 新增依赖

| 阶段 | 依赖 | 用途 | 是否必须 |
|---|---|---|---|
| Phase1 | 无新增 | 复用 reactor-core/Redisson/spring-ai | - |
| Phase2 | 无新增 | 复用 KafkaTemplate | - |
| Phase3 | 无新增 | DashScope rerank 用 RestTemplate | - |
| Phase4 | （可选）`spring-ai-tokencounter` | 精确 token 计数 | 可选，字符估算够用 |

> **结论：4 个阶段均无需新增 pom 依赖**，全部复用现有技术栈。

### 9.2 跨阶段风险汇总

| 风险 | 阶段 | 影响 | 缓解 |
|---|---|---|---|
| MVC 下 Flux 高并发线程开销 | Phase1 | 高并发下 servlet 线程耗尽 | 配置 async executor；长期可迁移 webflux |
| SSE 响应头已发送，异常无法走 GlobalExceptionHandler | Phase1 | 错误用户体验差 | Flux 内 onErrorResume 转 error 事件 |
| 异步索引延迟导致首次问答空召回 | Phase2 | 用户体验 | 单篇问答保留异步 ensureIndexed；前端提示重试 |
| DashScope rerank API 鉴权/可用性 | Phase3 | 精排失效 | 降级 LlmRerankService 或不精排 |
| 查询改写 3 次 LLM 调用增加延迟 | Phase4 | 首字延迟增大 | 并行化 + 简单问题跳过 HyDE |
| 两套 ES 索引长期割裂 | 全局 | 维护成本 | Phase3 用应用层 RRF 融合，不强制合并 |
| 限流 key 用 IP 易被 NAT 绕过 | Phase1 | 滥用 | Phase4 可加设备指纹/行为分析 |

---

## 10. 关键文件路径索引

### 10.1 现有关键文件（探索已确认）

| 类别 | 绝对路径 |
|---|---|
| RAG 服务 | `D:\resume-project\zhiguang_be\src\main\java\com\tongji\llm\rag\RagQueryService.java` |
| RAG 索引 | `D:\resume-project\zhiguang_be\src\main\java\com\tongji\llm\rag\RagIndexService.java` |
| LLM 配置 | `D:\resume-project\zhiguang_be\src\main\java\com\tongji\llm\LlmConfig.java` |
| 被注释 Controller | `D:\resume-project\zhiguang_be\src\main\java\com\tongji\knowpost\api\KnowPostRagController.java`、`KnowPostAiController.java` |
| 安全配置 | `D:\resume-project\zhiguang_be\src\main\java\com\tongji\auth\config\SecurityConfig.java` |
| 线程池 | `D:\resume-project\zhiguang_be\src\main\java\com\tongji\config\ThreadPoolConfig.java` |
| Redisson | `D:\resume-project\zhiguang_be\src\main\java\com\tongji\config\RedissonConfig.java` |
| ES 配置 | `D:\resume-project\zhiguang_be\src\main\java\com\tongji\config\ElasticsearchConfig.java`、`EsProperties.java` |
| 搜索服务 | `D:\resume-project\zhiguang_be\src\main\java\com\tongji\search\service\impl\SearchServiceImpl.java`、`index\SearchIndexService.java`、`index\SearchIndexInitializer.java` |
| 异常体系 | `D:\resume-project\zhiguang_be\src\main\java\com\tongji\common\exception\ErrorCode.java`、`BusinessException.java`、`common\web\GlobalExceptionHandler.java` |
| 发布流程 | `D:\resume-project\zhiguang_be\src\main\java\com\tongji\knowpost\service\impl\KnowPostServiceImpl.java` |
| Kafka 模式 | `D:\resume-project\zhiguang_be\src\main\java\com\tongji\search\outbox\CanalOutboxConsumerSearch.java`、`counter\event\CounterEventProducer.java` |
| 限流参考 | `D:\resume-project\zhiguang_be\src\main\java\com\tongji\counter\service\impl\CounterServiceImpl.java`（allowedByRateLimiter，第 383 行） |
| JWT 工具 | `D:\resume-project\zhiguang_be\src\main\java\com\tongji\auth\token\JwtService.java`（extractUserId） |
| 配置文件 | `D:\resume-project\zhiguang_be\src\main\resources\application.yml` |
| pom | `D:\resume-project\zhiguang_be\pom.xml` |
| 表结构 | `D:\resume-project\zhiguang_be\db\schema.sql` |

### 10.2 Phase1 新建文件（8 个）

| 文件 | 路径 |
|---|---|
| RagQaController | `D:\resume-project\zhiguang_be\src\main\java\com\tongji\llm\rag\api\RagQaController.java` |
| RagQaRequest | `...\llm\rag\api\dto\RagQaRequest.java` |
| RagQaMetaPayload | `...\llm\rag\api\dto\RagQaMetaPayload.java` |
| RagReference | `...\llm\rag\api\dto\RagReference.java` |
| RagErrorPayload | `...\llm\rag\api\dto\RagErrorPayload.java` |
| RagProperties | `...\llm\rag\config\RagProperties.java` |
| RagRateLimiter | `...\llm\rag\config\RagRateLimiter.java` |
| RagIndexExecutorConfig | `...\llm\rag\config\RagIndexExecutorConfig.java` |

### 10.3 Phase1 修改文件（6 个）

| 文件 | 改动 |
|---|---|
| `RagQueryService.java` | 重构为全库+单篇双模式，SSE 事件化，异常降级 |
| `KnowPostAiController.java` | 取消注释，恢复 AI 摘要 |
| `SecurityConfig.java` | 放行规则（全库问答 + 单篇问答 permitAll，reindex authenticated） |
| `ErrorCode.java` | 新增 RAG_NO_CONTEXT/RAG_LLM_FAILED/RAG_RATE_LIMITED/RAG_INDEX_FAILED |
| `application.yml` | async.request-timeout=-1 + rag 配置块 |
| `KnowPostRagController.java` | 保持注释（废弃），由 RagQaController 承接 |

---

> **报告结束**。本报告为只读分析产出，未对项目代码做任何修改。如需进入实施阶段，可按 Phase1 → Phase4 顺序逐步落地，每个阶段均可独立编译运行且向后兼容。
