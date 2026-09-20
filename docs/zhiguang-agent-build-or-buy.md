# 知光 Agent 项目：现状、需求与成品方案调研（Build or Buy）

> 版本：v0.1 ｜ 2026-09-19
> 关联文档：`zhiguang-agent-design.md` v0.5（范围冻结稿，四部分 + 五 ADR）
> 本文目的：回答"哪些自己写、哪些直接导入成品"，给出导入组合与修订里程碑。

---

## 1. 现状盘点（诚实版）

### 1.1 已在跑的资产

| 资产 | 状态 |
|---|---|
| zhiguang_be（Java Agentic RAG，:8080） | ✅ 运行中，Self-RAG/图谱/混合检索完整 |
| zhiguang_fe（React 18 前端） | ✅ 运行中，/qa 等页面 |
| Pi 本地安装（D:\code_project\pi） | ✅ CLI 与 Pi Web(:30141) 可用 |
| 模型通道 | ✅ 微信 chatapi DeepSeek-v4-flash，对话/工具调用/流式已验证 |

### 1.2 已产出

- 设计文档 v0.5：四部分范围冻结（三界面/多Session引擎/编排控制层/Skill体系）+ ADR-001~005 + 三层可视化

### 1.3 未开始（关键事实）

- **zhiguang_agent 模块尚未建骨架，自研 Agent 代码为零**
- 当前全部"Agent 能力"= 一个可 npm install 的依赖（Pi SDK 0.85.1）+ 一份设计文档
- 即：项目处于"设计完成、开发未动"状态

---

## 2. 需求复述（四部分 + 一横切）

| # | 部分 | 一句话 |
|---|---|---|
| ① | 三界面层 | 用户聊天页 + 超级管理员全局页 + 开发期调试面板 |
| ② | 多 Session 引擎 | 每用户隔离会话，Pi Session 每请求重建，MySQL 唯一真相 |
| ③ | 编排控制层 | JWT→策略（工具白名单/配额/内容安全）→ReAct→落库/审计 |
| ④ | Skill 体系 | zhiguang-* 业务 Skill + 渐进式披露 + 热开关 |
| 横切 | 工程流 | monorepo + git push 部署 + 黄金问题 eval 回归 |

---

## 3. 每部分 Build or Buy 判定

| 部分 | 判定 | 理由 |
|---|---|---|
| ① 三界面 | **买**（调试面板除外） | 聊天 UI/多用户/权限/管理后台是通用能力，成熟成品多 |
| ② 多 Session | **半买** | 会话存储与列表 UI 交给平台；"Pi Session 每请求重建 + 隔离策略"自研（薄层） |
| ③ 编排控制层 | **必自研** | 产品差异点：沙箱六铁律、配额、内容安全、JWT 桥接，无成品 |
| ④ Skill 体系 | **必自研** | Pi 原生机制 + 业务内容创作，无成品 |
| 横切 | 自研（已有） | git 流程现成 |

**结论：买 ①（顺带 ② 的存储/UI），自研 ③④ + 一个薄的 OpenAI 兼容端点。**

---

## 4. 成品候选调研

| 候选 | 定位 | 多用户+管理后台 | 接自定义后端 | 技术栈 | 结论 |
|---|---|---|---|---|---|
| **Open WebUI** | 自托管 ChatGPT 替代 UI | ✅ 多用户 + RBAC + 管理面板 | ✅ OpenAI 兼容自定义端点 + Pipelines/Functions | Python+Svelte，Docker 一键 | **① 首选** |
| **LibreChat** | 自托管对话平台 | ✅ 多用户 + 管理面板 | ✅ 自定义端点 + MCP + Agents | Node+React+Mongo | ① 备选 |
| Dify / FastGPT | LLMOps 全平台（自带 Agent 编排+知识库+后台） | ✅✅ | 不需要（它自己就是运行时） | Python/Node 重栈 | ❌ 与 Pi 路线冲突（替换运行时），v2 再评估 |
| LobeChat / NextChat | 轻量对话 UI | ❌ 多用户/后台弱 | ✅ | 前端为主 | ❌ 不满足超管需求 |
| **Langfuse** | LLM 可观测（trace/eval/管理视图） | ✅ 自托管 | ✅ SDK 上报 | Python+Docker | 可选加件：L4 系统健康/trace 钻取 |
| **PiThagoras** | Pi 无人值守任务门户（服务端持有 run + SSE 回放 + 容器沙箱 + IM 频道） | ❌ 单共享密码、无多用户、无超管；README 自述"应留在 LAN/Tailscale，不上公网" | ✅ 自身即 Pi 运行时（pi --mode rpc） | Node+Vite+SQLite+Docker | ❌ 不作 C 端产品 UI；借鉴三模式（§4.1），可选作内部运维门户 |

### 4.1 PiThagoras 评估（2026-09-19 补充）

**定位判定**：是成品，但是"另一个场景的成品"——个人/团队**无人值守编码任务门户**（给任务→关浏览器→回来看结果），非多用户 SaaS。架构：Browser ─SSE(replay+tail)─▶ portal ─JSONL stdio─▶ `pi --mode rpc`，SQLite 存会话+全事件日志。

**对照四部分**：① 有 Web UI 但单密码、无多用户/超管 → 不满足；② 服务端持有 session + workspace 隔离，但重启中断在跑 run（弱于 ADR-001）→ 部分满足；③ container executor 每任务沙箱（dropped caps + no-new-privileges + 内存/CPU/PID 上限）→ v2 沙箱参考；④ Pi 原生 Skill + 包管理 UI（npm/git/URL 安装）→ 超管热开关交互参考。

**借鉴三模式**（写入设计，不抄代码）：
1. SSE 事件 id 回放（`?since=`）→ zhiguang_agent SSE 断线续传协议
2. container executor 参数集（TASK_MEMORY_MB/CPUS/PIDS_LIMIT）→ v2 代码执行沙箱参考实现
3. Skill 包管理 UI 交互 → M6 超管 Skill 热开关界面参考

**法律提醒**：仓库**无 LICENSE 文件**（默认保留所有权利）——借鉴架构思路可行，逐行复制代码有侵权风险。

**可选用途**：部署为内部运维/调试门户（L2 替代，单密码模型恰好适合内部工具）；但 debug.html 更轻，暂不推荐。

调研来源见第 9 节。

---

## 5. 推荐导入组合（目标架构）

```
用户浏览器 → Open WebUI（登录/多用户/聊天UI/管理后台，Docker 部署，锁版本）
                ↓ OpenAI 兼容流式协议
            zhiguang_agent（自研薄层，Node，:3001）
                ├─ ③ 策略：JWT 桥接 / 配额 / 内容安全 / 沙箱白名单
                ├─ ② Pi SDK：Session 每请求重建 + 用户隔离
                ├─ ④ Skill 渐进式披露 + Extension 沙箱工具
                └─ search_go_knowledge → zhiguang_be RAG（内网 HTTP）
开发期：debug.html（:3001/debug）仍为内部过程视图面板
可选：Langfuse ← zhiguang_agent trace 上报（M6'）
```

**导入后消失的工作量**：原 M5 自研 /agent 页面、原 M6 自研 /admin 页面 → 由 Open WebUI 承担。
**保留的工作量**：M2/M3/M4 核心 + debug.html + 协议适配层。

---

## 6. 修订里程碑（对比设计文档 v0.5）

| 阶段 | 内容 | 变化 |
|---|---|---|
| M2 | 多 Session 隔离验证脚本 | 不变 |
| M3 | Extension + Skill 接通 Java RAG（Pi Web 切目录验证） | 不变 |
| M4 | zhiguang_agent 端点：OpenAI 兼容流式 + 策略 + debug.html | 加协议适配 |
| M5' | Open WebUI 部署 + 自定义端点接入 + 品牌/主题 | 替代原 M5（1-2 天 vs 原 5-7 天） |
| M6' | 配额/内容安全接入 + 超管验收 +（可选）Langfuse | 替代原 M6 自研后台 |
| M7 | 联调演示 + eval 回归 | 不变 |

**工期估算（动手天数，2026-09-19 细化）**：
- 全自研（不用成品）= **15-20 个动手天**：M2 0.5 + M3 1-1.5 + M4（编排+debug.html）3-4 + M5 用户页 3-4 + M6 超管页（含 Java 侧角色/表）5-7 + M7 2-3
- 导入路线（Open WebUI 承接 M5/M6）= **8-12 个动手天**
- 换算日历：全职协作（每天 6-8h）全自研 3-4 周 / 导入 2 周；业余节奏（每天 2-3h）全自研 2-3 个月 / 导入 1-1.5 个月
- 差值 ≈ 7-8 个动手天，集中在用户页与超管页两块

---

## 7. 风险与回退

| 风险 | 缓解 |
|---|---|
| R1 Open WebUI 对 Agent 工具时间线展示弱 | 用户侧只显示状态文本；过程钻取走 debug.html / Langfuse |
| R2 平台升级不兼容 | Docker 镜像 tag 锁版本，不追新 |
| R3 OpenAI 兼容协议承载不了自定义 agent_step 事件 | 降级为状态文本；debug.html 保留为唯一完整过程视图 |
| R4 集成失败/缺口过大 | 回退设计文档 v0.5 自研三界面路线（章节全部有效，无沉没成本） |
| R5 Python 栈与现有 Java/Node 运维异构 | Docker Compose 统一编排 |

---

## 8. 待拍板

- **D7**：UI 路线 = Open WebUI 导入（推荐） vs v0.5 自研三界面
- **D8**：zhiguang_fe 是否保留 /agent 入口（跳转/内嵌 Open WebUI，或干脆移除）
- **D9**：是否引入 Langfuse（建议 M6' 引入）

---

## 9. 调研来源

- [Open WebUI 官网](https://openwebui.com/)
- [open-webui @ PyPI（0.10.x）](https://pypi.org/project/open-webui/)
- [LibreChat 管理面板文档](https://www.librechat.ai/zh/docs/features/admin_panel)
- [LibreChat MCP 文档](https://www.librechat.ai/zh/docs/features/mcp)
- [LibreChat 智能体文档](https://www.librechat.ai/zh/docs/features/agents)
- [LobeChat vs Open WebUI vs LibreChat 对比](https://blog.elest.io/the-best-open-source-chatgpt-interfaces-lobechat-vs-open-webui-vs-librechat/)
- [Dify/RAGFlow/MaxKB/FastGPT/OpenWebUI 详细对比](https://blog.csdn.net/awei0916/article/details/146397467)
- [2026 开源 ChatGPT 替代盘点](https://pinggy.io/blog/best_open_source_alternatives_to_chatgpt/)
