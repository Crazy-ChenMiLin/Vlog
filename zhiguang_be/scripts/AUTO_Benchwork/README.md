# RAG Benchmark

本目录只负责知光 RAG 的可重复评测，不承载业务检索代码。

## 目录

```text
AUTO_Benchwork/
├─ pipeline/                 # 线上采集、报告、裁判与五场景汇总
├─ datasets/five_scenario/   # 五套受审 Gold、答案参考和人工审核记录
├─ tools/t2/                 # T2Retrieval 导入、向量化、检索评测和选题工具
├─ tests/                    # Benchmark 与 T2 工具的自动化测试
└─ baseline/                 # 人工确认后提交的回归基线
```

## 正式入口

提交信息包含 `[run-bench]` 时，GitHub Action 调用五场景总入口：

```bash
python scripts/AUTO_Benchwork/pipeline/run_five_scenario_benchmark.py ...
```

总入口按清单串行运行五个专题；每个专题内部依次调用 `benchmark.py`、
`report_generator.py` 和 `judge.py`。单个专题失败不会阻止后续专题，运行状态、
五场景汇总和全部逐题产物都会保留在 Artifact 中。

本地运行相同的五场景入口：

```bash
python scripts/AUTO_Benchwork/pipeline/run_five_scenario_benchmark.py \
  --base-url http://127.0.0.1:18080 \
  --run-id five-scenario-001 \
  --output-dir target/rag-benchmark/five-scenario-001
```

五场景清单位于 `datasets/five_scenario/suite-v1.json`，总审核记录位于
`datasets/five_scenario/review-v1.md`。每个专题目录固定包含：

- `gold-v1.json`：运行时读取的机器 Gold
- `answer-reference-v1.json`：答案裁判参考
- `source-selection-v1.json`：从 T2 qrels 选题的留档
- `review-v1.md`：人工可读审核记录

生成文件统一写入 `target/rag-benchmark/`，不要放回本目录。

## 测试

```bash
python -m unittest discover -s scripts/AUTO_Benchwork/tests -p "test_*.py"
```
