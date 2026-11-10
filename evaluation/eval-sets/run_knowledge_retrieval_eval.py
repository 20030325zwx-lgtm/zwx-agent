#!/usr/bin/env python3
"""知识库检索离线评测 runner（计划 v2 阶段 1，见 docs/design/2026-11-10-knowledge-module-flow-map.md §6）。

用法:
  python3 run_knowledge_retrieval_eval.py \
      --base-url http://127.0.0.1:8123/api \
      --token "$ASTER_INTERNAL_TOKEN" \
      --set knowledge-retrieval-golden-v1.json \
      --out baseline-2026-11-11.json

题集格式（knowledge-retrieval-golden-v1.json）:
  {"cases": [{
      "id": "text-001",
      "category": "text | table | figure | term | negative",
      "query": "退款政策是多少天？",
      "expected": {"documentId": "...", "blockId": "...", "sectionPath": "..."},  # negative 题为 null
      "answerSnippet": "可选：若给出，则检查返回 context 是否包含该片段"
  }]}

指标:
  - 块级 recall@k（宽判：命中 blockId **或** 其 sectionPath 的任一候选，避免分块粒度一调基线作废）
  - MRR（同宽判口径，取首个命中候选的名次倒数）
  - 各通道首次命中贡献（vector/lexical/fused/rerank 谁先命中）
  - 上下文含答案率（answerSnippet in context）
  - 负例误检率（negative 题返回非空候选的比例；目标越低越好）
  - P50/P95 延迟

基线惯例：结果 JSON 落 evaluation/eval-sets/baseline-*.json；后续每个检索改动用同一套题对比，
完成标准见设计文档阶段 1。
"""
import argparse
import json
import statistics
import sys
import time
import urllib.request

CHANNELS = ("vectorRank", "lexicalRank", "fusedRank", "rerankRank")


def call_retrieval(base_url, token, tenant_id, agent_key, query, timeout=30):
    body = json.dumps({"tenantId": tenant_id, "agentKey": agent_key, "query": query}).encode()
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}/internal/eval/retrieval",
        data=body,
        headers={"Content-Type": "application/json", "X-Aster-Internal-Token": token},
        method="POST")
    started = time.perf_counter()
    with urllib.request.urlopen(request, timeout=timeout) as response:
        payload = json.loads(response.read())
    return payload, (time.perf_counter() - started) * 1000.0


def candidate_hit(candidate, expected):
    """宽判：blockId 精确命中，或 sectionPath 命中（含 section 父级语义）。"""
    if not expected:
        return False
    block_id = expected.get("blockId")
    section_path = expected.get("sectionPath")
    if block_id and candidate.get("blockId") == block_id:
        return True
    if section_path and candidate.get("sectionPath") == section_path:
        return True
    return False


def first_hit_rank(candidates, expected):
    for index, candidate in enumerate(candidates, start=1):
        if candidate_hit(candidate, expected):
            return index
    return None


def hit_channel(candidates, expected):
    """返回最早命中候选上第一个非空通道（谁把这块资料带进来的）。"""
    for candidate in candidates:
        if not candidate_hit(candidate, expected):
            continue
        for channel in CHANNELS:
            if candidate.get(channel):
                return channel
        return "rerank-only"
    return None


def evaluate(cases, base_url, token, tenant_id, agent_key):
    rows, latencies = [], []
    for case in cases:
        payload, latency_ms = call_retrieval(base_url, token, tenant_id, agent_key, case["query"])
        latencies.append(latency_ms)
        trace = payload.get("trace") or {}
        candidates = trace.get("candidates") or []
        expected = case.get("expected") or {}
        is_negative = case.get("category") == "negative"
        rank = None if is_negative else first_hit_rank(candidates, expected)
        row = {
            "id": case.get("id"),
            "category": case.get("category"),
            "query": case["query"],
            "latencyMs": round(latency_ms, 1),
            "status": payload.get("status"),
            "candidateCount": len(candidates),
            "hit": rank is not None,
            "hitRank": rank,
            "hitChannel": None if is_negative else hit_channel(candidates, expected),
            "contextContainsAnswer": (case.get("answerSnippet") or "") in (payload.get("context") or ""),
        }
        if is_negative:
            # 负例：期望检索空手，候选非空记为误检（回答侧是否说"不知道"由全链路评测另测）
            row["hit"] = len(candidates) > 0
            row["falseRetrieval"] = len(candidates) > 0
        rows.append(row)
    return rows, latencies


def summarize(rows, latencies):
    positive = [row for row in rows if row["category"] != "negative"]
    negative = [row for row in rows if row["category"] == "negative"]
    hit_ranks = sorted(row["hitRank"] for row in positive if row["hit"])
    summary = {
        "cases": len(rows),
        "blockRecallAny": round(sum(row["hit"] for row in positive) / len(positive), 4) if positive else None,
        "mrr": round(sum(1.0 / rank for rank in hit_ranks) / len(positive), 4) if positive else None,
        "contextContainsAnswer": round(sum(row["contextContainsAnswer"] for row in positive) / len(positive), 4) if positive else None,
        "negativeFalseRetrievalRate": round(sum(bool(row.get("falseRetrieval")) for row in negative) / len(negative), 4) if negative else None,
        "latencyP50Ms": round(statistics.median(latencies), 1) if latencies else None,
        "latencyP95Ms": round(sorted(latencies)[int(0.95 * (len(latencies) - 1))], 1) if latencies else None,
        "channelContribution": {},
    }
    for channel in CHANNELS + ("rerank-only",):
        count = sum(1 for row in positive if row["hitChannel"] == channel)
        if count:
            summary["channelContribution"][channel] = count
    return summary


def main():
    parser = argparse.ArgumentParser(description="知识库检索离线评测")
    parser.add_argument("--base-url", default="http://127.0.0.1:8123/api")
    parser.add_argument("--token", required=True, help="app.internal.token 配置值")
    parser.add_argument("--set", dest="eval_set", default="knowledge-retrieval-golden-v1.json")
    parser.add_argument("--tenant-id", default="default")
    parser.add_argument("--agent-key", default="super")
    parser.add_argument("--out", default=None, help="结果落盘 JSON（baseline-*.json 惯例）")
    args = parser.parse_args()

    with open(args.eval_set, encoding="utf-8") as handle:
        cases = json.load(handle).get("cases", [])
    if not cases:
        print("题集为空：先入库语料并在题集里补 cases（字段说明见文件头注释）。", file=sys.stderr)
        sys.exit(2)

    rows, latencies = evaluate(cases, args.base_url, args.token, args.tenant_id, args.agent_key)
    summary = summarize(rows, latencies)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    for row in rows:
        flag = "✓" if (row["hit"] != (row["category"] == "negative")) else "✗"
        print(f"{flag} [{row['category']:8}] {row['id']}: rank={row['hitRank']} "
              f"channel={row['hitChannel']} {row['latencyMs']}ms status={row['status']}")

    if args.out:
        with open(args.out, "w", encoding="utf-8") as handle:
            json.dump({"summary": summary, "rows": rows}, handle, ensure_ascii=False, indent=2)
        print(f"已落盘 {args.out}")


if __name__ == "__main__":
    main()
