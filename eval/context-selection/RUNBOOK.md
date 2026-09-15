# 上下文选择复现手册

所有命令从仓库根目录执行。输出路径应使用新的 `CS-*` 目录；脚本默认拒绝覆盖已有数据或报告。

## P0：数据准备与诊断输入

官方原始 JSON 可从 HotpotQA 主页取得。若官方 CMU 文件服务器不可达，可使用 `hotpotqa/hotpot_qa` 的 Hugging Face 镜像 Parquet；来源 URL、获取日期、文件 SHA-256 和镜像身份必须写入本地来源记录。

本次固定镜像及校验值在 `sources.json`。下载器拒绝接受校验不符的现有文件：

```bash
python3 eval/context-selection/download_hotpot.py \
  --output-dir local-data/eval/context-selection/raw
```

```bash
python3 eval/context-selection/prepare_hotpot.py \
  --train-source local-data/eval/context-selection/raw/train-00000-of-00002.parquet \
  --train-source local-data/eval/context-selection/raw/train-00001-of-00002.parquet \
  --validation-source local-data/eval/context-selection/raw/validation-00000-of-00001.parquet \
  --dev-size 200 --test-size 400 --seed 20260905 \
  --output local-data/eval/context-selection/datasets/hotpot-cs-v1.jsonl \
  --manifest local-data/eval/context-selection/datasets/hotpot-cs-v1.manifest.json

python3 eval/context-selection/validate_dataset.py \
  --dataset local-data/eval/context-selection/datasets/hotpot-cs-v1.jsonl \
  --output local-data/eval/context-selection/datasets/hotpot-cs-v1.validation.json
```

先只生成 30 道公共开发题的无 gold 快照：

```bash
python3 eval/context-selection/capture_candidates.py \
  --dataset local-data/eval/context-selection/datasets/hotpot-cs-v1.jsonl \
  --split public_dev --limit 30 \
  --output local-data/eval/context-selection/snapshots/CS-P0-public-dev30.jsonl

python3 eval/context-selection/validate_snapshots.py \
  --snapshots local-data/eval/context-selection/snapshots/CS-P0-public-dev30.jsonl \
  --output local-data/eval/context-selection/snapshots/CS-P0-public-dev30.validation.json
```

## 工具测试

```bash
python3 -m unittest discover -s eval/context-selection/tests -p 'test_*.py'
```

## P1：Java 回放与选择评分

`run_selection.py` 先离线编译生产 Java 选择器，再只把无 gold 候选快照传给 Java。第二个及后续臂可加 `--skip-compile`。

```bash
python3 eval/context-selection/run_selection.py \
  --input local-data/eval/context-selection/snapshots/CS-P0-public-dev30.jsonl \
  --output local-data/eval/context-selection/runs/CS-P0-public-dev30-CS-P.jsonl \
  --strategy CS-P --token-budget 1024 --max-chunks 10

python3 eval/context-selection/score_selection.py \
  --dataset local-data/eval/context-selection/datasets/hotpot-cs-v1.jsonl \
  --snapshots local-data/eval/context-selection/snapshots/CS-P0-public-dev30.jsonl \
  --arm CS-P=local-data/eval/context-selection/runs/CS-P0-public-dev30-CS-P.jsonl \
  --output local-data/eval/context-selection/runs/CS-P0-public-dev30-score.json
```

## P2/P3：冻结的开发参数网格

`development-grid.json` 在读取开发对比结果前固定 12 组 CS-C 和 4 组 MMR 参数。以下命令运行全部臂、统一评分并应用 5 个百分点继续门槛：

```bash
python3 eval/context-selection/run_development.py \
  --dataset local-data/eval/context-selection/datasets/hotpot-cs-v1.jsonl \
  --snapshots local-data/eval/context-selection/snapshots/CS-P0-public-dev200.jsonl \
  --output-dir local-data/eval/context-selection/runs/CS-DEV-main-v1

python3 eval/context-selection/compare_runs.py \
  --score local-data/eval/context-selection/runs/CS-DEV-main-v1/selection-score.json \
  --output local-data/eval/context-selection/runs/CS-DEV-main-v1/development-decision.json
```

只有开发 gate 通过才生成完整冻结测试回答。未运行命令不计为通过。

本轮 gate 已失败。敏感性只对开发集锁定后的最佳简单基线与最佳 CS-C 运行 512/2048 预算，结果保存在 `local-data/eval/context-selection/runs/CS-DEV-sensitivity-v1/`。P4 的零调用记录见 `run-budget.md`。
