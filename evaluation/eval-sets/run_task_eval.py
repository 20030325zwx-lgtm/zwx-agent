#!/usr/bin/env python3
"""任务级端到端评测：驱动本地后端跑真实任务，检验计划依赖、证据验收与闲聊直达。

与 run_intent_gate.py（纯意图、可进 CI）不同，本脚本依赖：
  1. 本地后端 http://127.0.0.1:8123/api 已启动（真实模型调用，耗时按分钟计）；
  2. 管理员账号（默认 admin/admin123，可用 ASTER_USER / ASTER_PASS 覆盖）。
适合手动回归与夜间任务，不适合作为 PR 门禁。

用法：
  python3 run_task_eval.py [输出.json] [--timeout 420] [--only task-002-research-report]

流程：建会话 → SSE 发任务 → 若出现 plan-proposed（多步计划确认门）自动代用户授权 resume
→ 收集回答/计划/事件 → 拉 run 详情核对断言（完成、依赖波次、文件产出、验收状态）→ 清理会话。
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = os.environ.get("ASTER_BASE", "http://127.0.0.1:8123/api")
EVAL_SET = os.path.join(HERE, "task-eval-v1.json")


def http_json(path, payload=None, token=None, method=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.load(response)


def resolve_admin_credentials():
    """管理员凭据：环境变量优先，其次回退读 Git 忽略的 frontend/.env.local（登录页自动填充同源，不回显）。"""
    user = os.environ.get("ASTER_USER", "").strip()
    password = os.environ.get("ASTER_PASS", "").strip()
    env_local = os.path.join(HERE, "..", "..", "frontend", ".env.local")
    try:
        with open(env_local, encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if not user and line.startswith("VITE_DEFAULT_ADMIN_USERNAME="):
                    user = line.split("=", 1)[1].strip()
                elif not password and line.startswith("VITE_DEFAULT_ADMIN_PASSWORD="):
                    password = line.split("=", 1)[1].strip()
    except OSError:
        pass
    return user or "admin", password or "admin123"


def login():
    user, password = resolve_admin_credentials()
    try:
        return http_json("/auth/login", {"username": user, "password": password})["token"]
    except Exception as exception:
        sys.exit(f"无法登录本地后端（{BASE}）：{exception}\n请确认后端已启动且账号可用。")


NAMED_EVENTS = ("run", "plan", "plan-proposed", "activity", "rag-trace", "usage",
                "interrupted", "confirmation-required", "generation-error")


def read_sse(stream, deadline):
    """按 SSE 规范消费流直到 [DONE]：事件以空行分发，多行 data 合并，返回 (事件列表, 回答文本)。"""
    events, answer = [], []
    name, data_lines, done = None, [], False

    def dispatch():
        data = "\n".join(data_lines).strip()
        if data == "[DONE]":
            return True
        if name in NAMED_EVENTS:
            events.append({"event": name, "data": data})
        elif not name and data:
            answer.append(data)
        return False

    for line in stream:
        if time.time() > deadline:
            break
        text = line.decode("utf-8", "replace").rstrip("\r\n")
        if text == "":
            if data_lines and dispatch():
                done = True
                break
            name, data_lines = None, []
        elif text.startswith(":"):
            continue
        elif text.startswith("event:"):
            name = text.split(":", 1)[1].strip()
        elif text.startswith("data:"):
            data_lines.append(text.split(":", 1)[1].strip())
    return events, ("".join(answer) if not done else "".join(answer))


def open_stream(path_with_query, token):
    req = urllib.request.Request(BASE + path_with_query, headers={
        "Authorization": "Bearer " + token, "Accept": "text/event-stream"})
    return urllib.request.urlopen(req, timeout=60)


def run_case(case, token, deadline):
    started = time.time()
    conversation = http_json("/ai/manus/conversations", {}, token)
    conversation_id = conversation["id"]
    result = {"id": case["id"], "conversation_id": conversation_id, "events": 0,
              "plan_proposed": False, "answer": "", "plan": [], "checks": {},
              "latency_seconds": 0.0, "errors": []}
    try:
        query = (f"/ai/manus/chat?conversationId={conversation_id}"
                 f"&message={urllib.parse.quote(case['task'])}"
                 f"&knowledgeSearch={str(case.get('knowledge', False)).lower()}"
                 f"&webSearch={str(case.get('web_search', False)).lower()}"
                 f"&clientRequestId=eval-{case['id']}-{int(started)}")
        events, answer = [], ""
        with open_stream(query, token) as stream:
            events, answer = read_sse(stream, deadline)
        run_id = next((e["data"] for e in events if e["event"] == "run"
                      and len(e["data"]) < 64 and " " not in e["data"]), None)
        plan_proposed = next((e for e in events if e["event"] == "plan-proposed"), None)
        result["plan_proposed"] = plan_proposed is not None
        # 计划确认门：评测驱动代用户授权（真实用户点「开始执行」）
        if plan_proposed is not None and run_id:
            web_search = str(case.get("web_search", False)).lower()
            with open_stream(f"/ai/manus/conversations/{conversation_id}/runs/{run_id}/resume?webSearch={web_search}", token) as stream:
                events2, answer2 = read_sse(stream, deadline)
            events += events2
            answer = answer2 or answer
        result["events"] = len(events)
        result["answer"] = answer
        plan_event = next((e for e in events if e["event"] == "plan"), None)
        if plan_event:
            try:
                parsed = json.loads(plan_event["data"])
                if isinstance(parsed, list):
                    result["plan"] = parsed
            except json.JSONDecodeError:
                result["errors"].append("plan 事件解析失败")
        # run 详情：验收状态与返工轮次
        if run_id:
            detail = http_json(f"/ai/manus/conversations/{conversation_id}/runs/{run_id}", token=token)
            result["run_status"] = detail.get("status")
            result["verification"] = (detail.get("verification") or {}).get("status", "")
            result["revision"] = detail.get("resumeCount", 0)
        result["latency_seconds"] = round(time.time() - started, 1)
    except Exception as exception:
        result["errors"].append(f"{type(exception).__name__}: {exception}")
    finally:
        try:
            http_json(f"/ai/manus/conversations/{conversation_id}", token=token, method="DELETE")
        except Exception:
            pass
    return result


def evaluate(result, case):
    checks = {}
    expect = case.get("expect", {})
    checks["answer_non_empty"] = bool(result["answer"].strip())
    plan = result.get("plan") or []
    if expect.get("casual_direct"):
        checks["no_plan"] = not plan and not result["plan_proposed"]
    if "min_plan_steps" in expect:
        checks["min_plan_steps"] = len(plan) >= expect["min_plan_steps"]
    if "max_plan_steps" in expect:
        checks["max_plan_steps"] = len(plan) <= expect["max_plan_steps"]
    if expect.get("has_dependencies"):
        checks["has_dependencies"] = any(step.get("dependsOn") for step in plan)
    if expect.get("expect_file"):
        import re
        pattern = re.compile(r"[\w\u4e00-\u9fa5.-]+\.(md|pdf|txt|docx|xlsx|csv|html)", re.I)
        haystack = result["answer"] + json.dumps(result["plan"], ensure_ascii=False)
        checks["file_mentioned"] = bool(pattern.search(haystack))
    if expect.get("verification") == "pass":
        checks["verification_pass"] = result.get("verification") == "pass"
    checks["no_errors"] = not result["errors"]
    result["checks"] = checks
    return all(checks.values())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", nargs="?", default="task-eval-result.json")
    parser.add_argument("--timeout", type=int, default=420, help="单例秒级上限")
    parser.add_argument("--only", default=None, help="只跑指定 case id")
    parser.add_argument("--min-pass", type=int, default=None, help="最少通过数，低于则退出码 1")
    args = parser.parse_args()
    with open(EVAL_SET, encoding="utf-8") as handle:
        eval_set = json.load(handle)
    cases = [case for case in eval_set["cases"] if not args.only or case["id"] == args.only]
    token = login()
    results = []
    for case in cases:
        print(f"▶ {case['id']}: {case['task'][:40]}…")
        result = run_case(case, token, time.time() + args.timeout)
        passed = evaluate(result, case)
        result["passed"] = passed
        results.append(result)
        failed = [name for name, ok in result["checks"].items() if not ok]
        print(f"  {'✅' if passed else '❌'} {result['latency_seconds']}s"
              + (f" 未过：{', '.join(failed)}" if failed else ""))
    summary = {
        "generated_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        "total": len(results),
        "passed": sum(1 for item in results if item["passed"]),
        "results": results,
    }
    with open(os.path.join(HERE, args.output), "w", encoding="utf-8") as handle:
        json.dump(summary, handle, ensure_ascii=False, indent=2)
    print(f"\n通过 {summary['passed']}/{summary['total']}，明细见 {args.output}")
    threshold = args.min_pass if args.min_pass is not None else summary["total"]
    sys.exit(0 if summary["passed"] >= threshold else 1)


if __name__ == "__main__":
    main()
