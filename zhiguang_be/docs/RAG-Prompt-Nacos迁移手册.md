# RAG Prompt 迁移到 Nacos 手册

> 目的：把 8 个硬编码在 Java 里的 RAG system prompt 抽到 Nacos AI Prompt 管理，**Nacos 成为唯一来源**，支持在线修改、版本化、准实时热更新。

## 一、迁移结果总览

| 项 | 变更 |
|---|---|
| Prompt 存储 | Nacos AI Prompt 管理（`/v3/admin/ai/prompt` 管理、`/v3/client/ai/prompt` 读取） |
| Namespace | `public`（默认） |
| 8 个 prompt | 全部 `version = 1.0.0`，状态已 **online** |
| 后端读取 | `RagPromptService`：登录 token 缓存 + TTL 缓存（60s）+ 拉不到直接抛异常 |
| 本地副本 | ❌ 无（已删除 `RagPromptDefaults`，代码里不再保留任何 prompt 原文） |

## 二、8 个 Prompt 清单

| # | Prompt Key | 用途 | 业务标签 |
|---|---|---|---|
| 1 | `rag-planner-system` | RAG 主 Agent 规划器（判问题类型/检索策略，JSON） | rag,agent,planner |
| 2 | `rag-evidence-system` | 证据检查（证据是否够，JSON） | rag,agent,evidence |
| 3 | `rag-final-answer-system` | 主回答（无历史） | rag,answer |
| 4 | `rag-final-answer-with-history-system` | 主回答（带历史） | rag,answer,history |
| 5 | `rag-rewrite-system` | 查询改写 | rag,rewrite |
| 6 | `rag-hyde-system` | HyDE 假设性答案 | rag,enhance,hyde |
| 7 | `rag-graph-understanding-system` | 图查询理解（抽实体/关系，JSON） | rag,graph |
| 8 | `rag-direct-answer-system` | 闲聊直答 | rag,answer,chat |

各 prompt 全文见 Nacos 控制台，或本仓库 `scripts/seed_nacos_prompts.py` 的 `PROMPTS` 列表。

## 三、代码改动

**新增 2 个类**（`com.tongji.llm.config`）：

| 类 | 职责 |
|---|---|
| `RagPromptProperties` | `rag.prompt.*` 配置（serverAddr/账号/cacheTtl） |
| `RagPromptService` | RestTemplate 调 Nacos API，token 缓存 + TTL 缓存，key 常量，`getSystemPrompt(key)` |

**改 7 个 service**（8 处 prompt）：`String system = """..."""` → `ragPromptService.getSystemPrompt(RagPromptService.KEY_XXX)`。

**配置**（`application.yml`）：

```yaml
rag:
  prompt:
    server-addr: ${NACOS_SERVER_ADDR:100.83.242.114:8848}
    username: ${NACOS_USERNAME:nacos}
    password: ${NACOS_PASSWORD:nacos}
    cache-ttl-seconds: 60            # 本地缓存秒数，过期自动重拉（准实时热更新）
```

## 四、读取链路

```
RagPromptService.getSystemPrompt(key)
  ├─ 缓存未过期 ─────────────────► 返回缓存
  ├─ 登录拿 token ──失败──► 抛异常
  ├─ GET /v3/client/ai/prompt ──失败/空──► 抛异常
  └─ 成功 ──────────────────────► 缓存并返回 Nacos 模板
```

**设计原则**：Nacos 是 prompt 唯一来源。拉不到就抛 `IllegalStateException` 让问题显式暴露，不做静默降级——这样 Nacos 里的改动/故障都能立刻被看到。

## 五、在 Nacos 控制台改 prompt

1. 打开 Nacos 控制台 → 左侧「AI 注册中心」→「Prompt 管理」
2. 找到目标 prompt key，进入编辑
3. 改「模板内容」，提交
4. **生效时间**：最多等 `cache-ttl-seconds`（默认 60 秒），后端下次 RAG 请求自动读到新模板，**无需重启**

## 六、验证热更新

1. 后端启动后，改 Nacos 里 `rag-planner-system` 加一句话（如末尾加 `FOO`）
2. 等 60 秒
3. 发一条 RAG 请求，确认 planner 用了新 prompt
4. 改回原样，再等 60 秒，确认恢复

## 七、注意事项

- **Nacos 不可用时 RAG 会报错**（`RAG prompt 'xxx' unavailable`）。这是有意的——保证 prompt 改动真实生效、不被旧值掩盖。
- **key 名不能改**：后端 `RagPromptService.KEY_*` 与 Nacos promptKey 一一对应，改 key 会找不到。
- 改 prompt 会影响 RAG 输出质量，建议配合 `scripts/rag-eval/` 的 `questions-40.json` 做回归评测。

## 八、首次灌数据脚本

`scripts/seed_nacos_prompts.py`：登录 → 创建 → force-publish → online，8 个一次灌完。
`scripts/verify_nacos_prompts.py`：校验 8 个 prompt 已 online 且客户端可读。

```bash
ssh root@100.83.242.114 'python3 -' < scripts/seed_nacos_prompts.py
```
