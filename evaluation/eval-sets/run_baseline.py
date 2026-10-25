#!/usr/bin/env python3
"""评测集基线运行：意图 12 例（qwen-plus 同款提示词）+ RAG 15 例（verify-retrieval 实测）。"""
import json, time, urllib.request, urllib.error

BASE = "http://127.0.0.1:8123/api"
key = next(l.strip().split(":", 1)[1].strip() for l in open("src/main/resources/application-local.yml") if l.strip().startswith("api-key:"))

# 登录拿 admin token
req = urllib.request.Request(BASE + "/auth/login", data=json.dumps({"username": "admin", "password": "admin123"}).encode(), headers={"Content-Type": "application/json"})
token = json.load(urllib.request.urlopen(req, timeout=15))["token"]
auth = {"Authorization": "Bearer " + token}

SYSTEM_PROMPT = """你是意图识别器。对用户消息做判断后，只输出一个 JSON 对象，禁止输出任何其他文字或代码块标记。字段：
intent：取值只能是 知识库问答、联网查询、工具任务、闲聊问答 之一；
confidence：0 到 1 的数字，你对意图判断的把握；
complexity：0 到 2 的数字（0=单轮可答，1=需要多步骤，2=需要研究分析）；
needs_knowledge：0 到 1 的数字，回答该问题需要检索私有知识库的概率。"""

def classify(question):
    body = json.dumps({"model": "qwen-plus", "temperature": 0.1, "max_tokens": 120,
                       "messages": [{"role": "system", "content": SYSTEM_PROMPT}, {"role": "user", "content": question}]}).encode()
    req = urllib.request.Request("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                                 data=body, headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"})
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=30) as resp:
        text = json.load(resp)["choices"][0]["message"]["content"]
    start, end = text.find("{"), text.rfind("}")
    node = json.loads(text[start:end + 1])
    return node, round(time.time() - t0, 2)

def verify(document_id, agent_key, question):
    body = json.dumps({"query": question}).encode()
    req = urllib.request.Request(f"{BASE}/ai/agent-knowledge/documents/{document_id}/verify-retrieval?agentKey={agent_key}",
                                 data=body, headers={**auth, "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.load(resp)

eval_set = json.load(open("evaluation/eval-sets/knowledge-intent-eval-v1.json", encoding="utf-8"))
results = {"name": "baseline-2026-10-26", "router": "qwen-plus（QwenIntentRouter 同款提示词）", "intent": [], "rag": []}

for case in [c for c in eval_set["cases"] if c["type"] == "intent"]:
    try:
        answer, latency = classify(case["question"])
        match = answer.get("intent") == case["expectedIntent"]
        results["intent"].append({"id": case["id"], "question": case["question"], "expected": case["expectedIntent"],
                                  "actual": answer.get("intent"), "confidence": answer.get("confidence"),
                                  "needsKnowledge": answer.get("needs_knowledge"), "match": match,
                                  "latencySeconds": latency, "notes": case["notes"]})
        print(("PASS " if match else "FAIL "), case["id"], "期望", case["expectedIntent"], "| 实际", answer.get("intent"), answer.get("confidence"), f"{latency}s")
    except Exception as e:
        results["intent"].append({"id": case["id"], "question": case["question"], "expected": case["expectedIntent"], "error": str(e), "match": False})
        print("ERR  ", case["id"], e)
    time.sleep(0.4)

for case in [c for c in eval_set["cases"] if c["type"] == "rag"]:
    ev = case["evidence"]
    try:
        verdict = verify(ev["documentId"], case["agentKey"], case["question"])
        matched = bool(verdict.get("matched"))
        results["rag"].append({"id": case["id"], "question": case["question"], "expectedDocument": ev["filename"],
                               "matched": matched, "notes": case["notes"]})
        print(("PASS " if matched else "FAIL "), case["id"], "证据", ev["filename"], "| matched:", matched)
    except Exception as e:
        results["rag"].append({"id": case["id"], "question": case["question"], "expectedDocument": ev["filename"], "error": str(e), "matched": False})
        print("ERR  ", case["id"], e)

intent_pass = sum(1 for r in results["intent"] if r.get("match"))
rag_pass = sum(1 for r in results["rag"] if r.get("matched"))
results["summary"] = {"intentPass": intent_pass, "intentTotal": len(results["intent"]),
                      "ragPass": rag_pass, "ragTotal": len(results["rag"]),
                      "avgIntentLatencySeconds": round(sum(r.get("latencySeconds", 0) for r in results["intent"]) / max(1, len(results["intent"])), 2)}
print("汇总：意图", intent_pass, "/", len(results["intent"]), "| RAG", rag_pass, "/", len(results["rag"]))

with open("evaluation/eval-sets/baseline-2026-10-26.json", "w", encoding="utf-8") as f:
    json.dump(results, f, ensure_ascii=False, indent=2)
    f.write("\n")
print("written evaluation/eval-sets/baseline-2026-10-26.json")
