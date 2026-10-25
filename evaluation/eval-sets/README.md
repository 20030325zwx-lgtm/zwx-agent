# 评测集（evaluation/eval-sets）

## 当前版本

- 评测集：[knowledge-intent-eval-v1.json](./knowledge-intent-eval-v1.json)（2026-10-26，27 例：意图 12 + RAG 15）
- 基线结果：[baseline-2026-10-26.json](./baseline-2026-10-26.json)（意图 11/12，RAG 15/15，qwen-plus 路由）
- 基线脚本：[run_baseline.py](./run_baseline.py)（意图走 DashScope 兼容模式 + QwenIntentRouter 同款系统提示词；RAG 走 `POST /ai/agent-knowledge/documents/{id}/verify-retrieval`）

## 用途

1. **意图分流批 2（闲聊短路）的门禁**：短路开启前，意图用例必须达到约定通过率（当前基线 11/12，唯一失败为刻意保留的题面模糊点 intent-011，见下）。
2. **意图题面校准**：失败用例暴露 criteria 措辞边界，修改 `QwenIntentRouter`/`JevIntentRouter` 的题面后重跑对比。
3. **RAG 检索策略对比**：改造检索（混合检索、重排）前后重跑 RAG 用例，命中/未命中对比是改造有效性的直接证据。
4. **回归门禁**：改动意图提示词、检索参数后必须重跑并附结果。

## 语料与范围

- `test` 范围：人文地理语料 31 篇（源文件在 `data/knowledge/super-human-geography`，已入库，幂等键 `n8n-eval-corpus-*`，自动发布，共 218 切片）。RAG 用例 rag-001~012。
- `super` 范围：员工手册（2024版）.pdf、G60 沪昆高速镇胜线交通事故梳理。RAG 用例 rag-013~015。

## 基线要点（2026-10-26）

- 意图平均延迟约 1.17s（qwen-plus，自报置信非校准）。
- **intent-011"黑龙江省的省会是哪座城市"判为 FAIL 是评测集设计使然**：按当前题面"常识问答归闲聊"，但语料已收录该事实且模型判知识库问答（置信 1.0）——模型判断其实更符合用户利益。该用例的用途是暴露"常识性问题是否该走知识库"这一题面模糊点，批 2 决策时重新评审归属，可能改注为知识库问答或调整题面。
- RAG 15/15 全命中：当前向量检索 + 重排在显式事实型问题上表现良好，作为混合检索改造的对照基线。

## 维护规则

- 新增用例必须带 `notes`（判定依据或模糊点说明）；改标注要改版本号并在本文件记录变更。
- 评测集 JSON 是唯一事实来源，基线结果 JSON 与评测集版本对应，不覆盖旧基线。
- 重跑方式：`python3 evaluation/eval-sets/run_baseline.py`（需要 `application-local.yml` 中的 DashScope Key 与本地后端在线）。
