#!/usr/bin/env python3
"""意图评测门禁：只跑意图 12 例，不依赖本地后端，可直接进 CI。

与 run_baseline.py 的区别：基线脚本还要跑 RAG（依赖本地后端与已入库语料），
本脚本只调 DashScope 兼容模式，因此可在 GitHub Actions 上作为批 2 短路的发布门禁。
系统提示词必须与 QwenIntentRouter 保持同源（修改路由题面时同步修改此处）。

用法：
  python3 run_intent_gate.py [输出.json] [--model qwen-plus] [--min-pass 11]

Key 来源：环境变量 DASHSCOPE_API_KEY（CI）；缺省回退读 application-local.yml（本地）。
无 Key 时退出码 0 并提示跳过，保证无密钥的 fork/PR 不被门禁卡死。
"""
import json
import os
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
EVAL_SET = os.path.join(HERE, "knowledge-intent-eval-v1.json")

# 与 QwenIntentRouter.buildRequest 同源；改动任一侧必须同步另一侧并在评测 README 记录。
SYSTEM_PROMPT = """你是意图识别器。对用户消息做判断后，只输出一个 JSON 对象，禁止输出任何其他文字或代码块标记。字段：
intent：取值只能是 知识库问答、联网查询、工具任务、闲聊问答 之一；
confidence：0 到 1 的数字，你对意图判断的把握；
complexity：0 到 2 的数字（0=单轮可答，1=需要多步骤，2=需要研究分析）；
needs_knowledge：0 到 1 的数字，回答该问题需要检索私有知识库的概率。"""


def resolve_key():
    key = os.environ.get("DASHSCOPE_API_KEY", "").strip()
    if key:
        return key, "env"
    local_yml = os.path.join(HERE, "..", "..", "src", "main", "resources", "application-local.yml")
    try:
        with open(local_yml, encoding="utf-8") as handle:
            for line in handle:
                if line.strip().startswith("api-key:"):
                    return line.split(":", 1)[1].strip(), "application-local.yml"
    except OSError:
        pass
    return None, None


def classify(key, base_url, model, question):
    body = json.dumps({
        "model": model,
        "temperature": 0.1,
        "max_tokens": 120,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": question},
        ],
    }).encode()
    req = urllib.request.Request(
        base_url + "/compatible-mode/v1/chat/completions",
        data=body,
        headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"})
    started = time.time()
    with urllib.request.urlopen(req, timeout=30) as resp:
        text = json.load(resp)["choices"][0]["message"]["content"]
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end <= start:
        raise ValueError("no JSON object in response: " + text[:120])
    node = json.loads(text[start:end + 1])
    return node, round(time.time() - started, 2)


def main():
    args = sys.argv[1:]
    output = "evaluation/eval-sets/ci-intent-result.json"
    positional = []
    model = "qwen-plus"
    min_pass = 11
    i = 0
    while i < len(args):
        if args[i] == "--model":
            model = args[i + 1]
            i += 2
        elif args[i] == "--min-pass":
            min_pass = int(args[i + 1])
            i += 2
        else:
            positional.append(args[i])
            i += 1
    if positional:
        output = positional[0]

    key, key_source = resolve_key()
    if not key:
        print("SKIP  未找到 DASHSCOPE_API_KEY（env 与 application-local.yml 均无），意图门禁跳过")
        return 0

    eval_set = json.load(open(EVAL_SET, encoding="utf-8"))
    cases = [c for c in eval_set["cases"] if c["type"] == "intent"]
    base_url = "https://dashscope.aliyuncs.com"
    results = {"name": "ci-intent-gate", "model": model, "minPass": min_pass, "cases": []}
    passed = 0
    failed = 0
    for case in cases:
        try:
            answer, latency = classify(key, base_url, model, case["question"])
            match = answer.get("intent") == case["expectedIntent"]
            results["cases"].append({
                "id": case["id"], "expected": case["expectedIntent"], "actual": answer.get("intent"),
                "confidence": answer.get("confidence"), "match": match, "latencySeconds": latency})
            print(("PASS " if match else "FAIL "), case["id"], "期望", case["expectedIntent"],
                  "| 实际", answer.get("intent"), answer.get("confidence"), f"{latency}s")
        except Exception as exc:  # 网络错误也算失败，门禁要真实反映可用性
            results["cases"].append({"id": case["id"], "expected": case["expectedIntent"],
                                     "error": str(exc), "match": False})
            print("ERR  ", case["id"], exc)
        time.sleep(0.4)

    passed = sum(1 for c in results["cases"] if c.get("match"))
    failed = len(results["cases"]) - passed
    results["passed"] = passed
    results["total"] = len(results["cases"])
    os.makedirs(os.path.dirname(output) or ".", exist_ok=True)
    with open(output, "w", encoding="utf-8") as handle:
        json.dump(results, handle, ensure_ascii=False, indent=2)
    print(f"SUMMARY 意图 {passed}/{results['total']}（门禁要求 ≥{min_pass}）key={key_source}")
    if passed < min_pass:
        print("GATE FAILED 意图评测未达标，禁止合并/发布（见 evaluation/eval-sets/README.md 门禁规则）")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
