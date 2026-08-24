# T2Retrieval 端到端检索评测

这里保存 T2Retrieval 的独立导入、向量化、评测和专题 Gold 构建工具。语料直接使用现有索引 `zhiguang-ai-index`，并用 `metadata.dataset=mteb/T2Retrieval` 标记导入文档。

## 准备

在 `zhiguang_be` 目录执行：

```bash
python3 -m pip install -r scripts/AUTO_Benchwork/public_dataset/requirements.txt
export NVIDIA_API_KEY='your-key'
```

如果 Elasticsearch 不在本机，可设置 `T2_ES_URL`、`T2_ES_USERNAME`和 `T2_ES_PASSWORD`。模型可用 `T2_EMBEDDING_MODEL` 覆盖，默认是 `nvidia/nv-embed-v1`。

Parquet 默认位置：

```text
target/rag-benchmark/public-datasets/t2retrieval/raw/corpus.parquet
target/rag-benchmark/public-datasets/t2retrieval/raw/queries.parquet
target/rag-benchmark/public-datasets/t2retrieval/raw/qrels.parquet
```

## 1. 导入 corpus

```bash
python3 scripts/AUTO_Benchwork/public_dataset/import_t2_corpus.py \
  --report target/rag-benchmark/t2-corpus-import-report.json
```

脚本直接把 T2 已切好的 corpus 写入现有索引；Elasticsearch `_id`、`id`、`metadata.chunkId` 均使用 T2 corpus ID。重复运行会覆盖同 ID 的 T2 文档，不会不断制造副本。

## 2. 生成并回写向量

```bash
python3 scripts/AUTO_Benchwork/public_dataset/embed_t2_corpus.py \
  --report target/rag-benchmark/t2-embedding-report.json
```

默认只处理没有 `embedding` 的 T2 文档，中断后直接重跑即可续传。需要全量重算时加 `--overwrite`。文档向量使用 `input_type=passage`。

## 3. 评测

```bash
python3 scripts/AUTO_Benchwork/public_dataset/evaluate_t2_retrieval.py \
  --limit 40
```

默认用固定随机种子抽 40 题，同时评测 BM25、向量检索和本地 RRF 融合，输出：

```text
target/rag-benchmark/t2-retrieval-report.json
target/rag-benchmark/t2-retrieval-report.md
```

全量 22,812 题使用 `--limit 0`。查询向量使用 `input_type=query`。报告指标包含 `Recall@K`、`Hit@K` 和 `MRR@最大K`，并记录每题排名及异常样本。

## 4. 汽车专题 Gold

首个受审专题为“汽车维护与故障诊断”，共 40 题：

```text
t2-topic-automotive-maintenance-v1.json  # 受审选题、规范问题和固定证据
gold-dataset-t2-automotive-v1.json       # 专题 Gold 留档
gold-dataset-t2-automotive-v1.md         # 人工审核文档
../gold-dataset-v1.json                  # 后端与 Action 当前实际读取的正式 Gold
```

正式 Gold 的 40 题均为 `approved`，运行报告中的数据集版本为 `t2-automotive-maintenance-v1`。专题留档与正式 Gold 内容应保持一致。

## 常用覆盖参数

```bash
--es-url http://127.0.0.1:9200
--index zhiguang-ai-index
--embedding-model nvidia/nv-embed-v1
--embedding-batch-size 8
--embedding-batch-max-chars 9000
--embedding-input-max-chars 2000
--ks 1 5 10 20
--modes bm25 vector hybrid
```
