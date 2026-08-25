# T2Retrieval 数据工具

这里是一次性或低频使用的数据准备工具，不属于线上 Benchmark 流水线。语料写入现有
`zhiguang-ai-index`，并以 `metadata.dataset=mteb/T2Retrieval` 标识。

在 `zhiguang_be` 目录安装依赖：

```bash
python3 -m pip install -r scripts/AUTO_Benchwork/tools/t2/requirements.txt
```

默认 Parquet 位置：

```text
target/rag-benchmark/public-datasets/t2retrieval/raw/corpus.parquet
target/rag-benchmark/public-datasets/t2retrieval/raw/queries.parquet
target/rag-benchmark/public-datasets/t2retrieval/raw/qrels.parquet
```

## 工具职责

| 文件 | 用途 |
| --- | --- |
| `import_t2_corpus.py` | 将 T2 corpus 按原始 ID 幂等写入 Elasticsearch |
| `embed_t2_corpus.py` | 为尚无向量的 T2 文档生成并回写 embedding |
| `evaluate_t2_retrieval.py` | 评测 BM25、向量和 RRF 的 Recall、Hit、MRR |
| `build_t2_topic_gold.py` | 从 queries、qrels、corpus 生成候选专题 Gold |
| `t2_common.py` | 上述工具共用的配置和访问函数 |

## 常用命令

```bash
python3 scripts/AUTO_Benchwork/tools/t2/import_t2_corpus.py \
  --report target/rag-benchmark/t2-corpus-import-report.json

python3 scripts/AUTO_Benchwork/tools/t2/embed_t2_corpus.py \
  --report target/rag-benchmark/t2-embedding-report.json

python3 scripts/AUTO_Benchwork/tools/t2/evaluate_t2_retrieval.py --limit 40
```

`build_t2_topic_gold.py` 的默认候选输出位于
`target/rag-benchmark/generated/`，不会直接覆盖已经人工审核的正式数据集。
Elasticsearch 地址、账号、模型和密钥均通过环境变量或命令行参数提供，不写入仓库。

