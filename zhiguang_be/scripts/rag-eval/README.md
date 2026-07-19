# RAG Eval

这个目录把 RAG 召回评估做成可重复命令，避免每次手动启动后端、切配置、跑脚本、再人工统计。

## 一键跑 BM25 A/B

```powershell
.\scripts\rag-eval\run-rag-ab.ps1
```

默认会做这些事：

1. 用 `rag.retrieval.bm25-enabled=false` 启动后端。
2. 跑 `questions-40.json` 里的同一批问题。
3. 保存 `bm25-off.json`。
4. 停掉 off 后端。
5. 用 `rag.retrieval.bm25-enabled=true` 启动后端。
6. 保存 `bm25-on.json`。
7. 自动生成 `compare.json` 和 `compare.md`。

输出目录默认在：

```text
target\rag-eval\<run-name>\
```

常用参数：

```powershell
.\scripts\rag-eval\run-rag-ab.ps1 `
  -RunName "local-rag-ab-40q" `
  -Questions ".\scripts\rag-eval\questions-40.json" `
  -TopK 10 `
  -OffPort 18180 `
  -OnPort 18181
```

脚本会自动选择 Maven：

- 如果本机存在 `D:\Maven\apache-maven-3.9.11\bin\mvn.cmd`，优先用它。
- 否则使用 PATH 里的 `mvn`，适合 GitHub Action / Linux / self-hosted runner。

## 追加到 Obsidian 文档

```powershell
.\scripts\rag-eval\run-rag-ab.ps1 `
  -AppendMarkdownTo "C:\Users\26487\OneDrive\应用\remotely-save\Obsidian Vault\米林的项目们\RAG\笔记\优化方向\召回率优化.md"
```

## 单独跑一次 debug 评估

```powershell
python .\scripts\rag-eval\run-rag-debug-eval.py `
  --base-url http://localhost:8080 `
  --questions .\scripts\rag-eval\questions-40.json `
  --output .\target\rag-eval\single.json `
  --label local-debug
```

## 单独比较两份结果

```powershell
python .\scripts\rag-eval\compare-rag-ab.py `
  --off .\target\rag-eval\bm25-off.json `
  --on .\target\rag-eval\bm25-on.json `
  --output-json .\target\rag-eval\compare.json `
  --output-md .\target\rag-eval\compare.md
```

## 当前判断口径

- `TEST_QUESTION` 和 `INTERVIEW_TEMPLATE` 被视为 bad section。
- `rerankTop1` 相同则记为不变。
- 从 bad section 变到非 bad section 记为变好。
- 从非 bad section 变到 bad section 记为变差。
- `BACKGROUND -> CONCEPT` 记为变好。
- `CONCEPT -> BACKGROUND` 记为变差。
- 其他变化记为需人工复核。

这个口径是为了先把自动化闭环跑起来，后续可以加入 gold chunk 标注，让判断更准确。

## GitHub Action 手动触发

workflow 文件：

```text
.github/workflows/rag-eval.yml
```

触发方式：

```text
GitHub -> Actions -> RAG evaluation -> Run workflow
```

它会：

1. 安装 Java 21。
2. 安装 Python 和 `scripts/rag-eval/requirements.txt`。
3. 启动 `bm25-off` 后端并跑评估。
4. 启动 `bm25-on` 后端并跑评估。
5. 生成 `target/rag-eval/<run-name>/compare.md` 和 `compare.json`。
6. 上传整个 `target/rag-eval` 作为 artifact。

注意：

```text
这个 workflow 默认 runs-on: self-hosted。

原因是完整 RAG 评估依赖真实 MySQL / Redis / Elasticsearch / 向量索引 / 外部模型服务。
GitHub 官方 ubuntu-latest 通常不能直接访问你的内网中间件。
```

如果 self-hosted runner 需要覆盖配置，可以在 GitHub Secrets 里配置：

```text
RAG_EVAL_ENV
```

格式是多行 `KEY=value`：

```text
SPRING_DATASOURCE_URL=jdbc:mysql://...
SPRING_DATASOURCE_USERNAME=...
SPRING_DATASOURCE_PASSWORD=...
SPRING_DATA_REDIS_HOST=...
SPRING_ELASTICSEARCH_URIS=...
SPRING_ELASTICSEARCH_USERNAME=...
SPRING_ELASTICSEARCH_PASSWORD=...
SPRING_AI_OPENAI_EMBEDDING_API_KEY=...
RAG_RERANK_API_KEY=...
```

不要把这些密钥写进仓库。

## 推荐使用方式

```text
本地开发：
改 RAG / BM25 / RRF / rerank 后，直接跑 run-rag-ab.ps1。

GitHub：
不要每次 push 都跑完整 40 题。
需要评估时手动触发 RAG evaluation workflow。

后续如果要做 PR 门禁：
再新增一个 5-10 题 smoke questions 文件，跑轻量版。
```
