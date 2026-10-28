#!/usr/bin/env python3
"""任务级端到端评测：驱动本地后端跑真实任务，检验计划依赖、证据验收与闲聊直达。

与 run_intent_gate.py（纯意图、可进 CI）不同，本脚本依赖：
  1. 本地后端 http://127.0.0.1:8123/api 已启动（真实模型调用，耗时按分钟计）；
  2. 一个隔离的评测账号（默认 eval-bot/eval-bot-local，可用 ASTER_EVAL_USER / ASTER_EVAL_PASS 覆盖）。
     首次运行自动注册；登录失败且注册被拒时回退管理员账号并打印警告。
    使用专用账号可避免污染真实用户的记忆、会话与工作区。
适合手动回归与夜间任务，不适合作为 PR 门禁。

用法：
  python3 run_task_eval.py [输出.json] [--timeout 420] [--only task-002-research-report]

流程：建会话 → SSE 发任务（permissionMode=YOLO，计划门直接放行）→ 收集回答/计划/事件
→ 拉 run 详情与轨迹核对断言 → 失败样本落 artifacts/<日期>-<case>/ → 清理会话。
评分由 score_task_result.py 独立完成，可对历史结果重放。
"""
import argparse
import hashlib
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

from score_task_result import evaluate, score_summary

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
BASE = os.environ.get("ASTER_BASE", "http://127.0.0.1:8123/api")
EVAL_SET = os.path.join(HERE, "task-eval-v1.json")
ARTIFACT_DIR = os.path.join(HERE, "artifacts")
PERMISSION_MODE = "YOLO"


def http_json(path, payload=None, token=None, method=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.load(response)


def read_admin_candidates():
    """管理员凭据候选：环境变量 → frontend/.env.local → 内置默认；逐个尝试直到登录成功。"""
    candidates = []
    user = os.environ.get("ASTER_USER", "").strip()
    password = os.environ.get("ASTER_PASS", "").strip()
    if user and password:
        candidates.append((user, password))
    env_local = os.path.join(ROOT, "frontend", ".env.local")
    local_user = local_password = ""
    try:
        with open(env_local, encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if line.startswith("VITE_DEFAULT_ADMIN_USERNAME="):
                    local_user = line.split("=", 1)[1].strip()
                elif line.startswith("VITE_DEFAULT_ADMIN_PASSWORD="):
                    local_password = line.split("=", 1)[1].strip()
    except OSError:
        pass
    if local_user and local_password:
        candidates.append((local_user, local_password))
    candidates.append(("admin", "admin123"))
    return candidates


def try_login(user, password):
    try:
        return http_json("/auth/login", {"username": user, "password": password})["token"]
    except Exception:
        return None


def admin_session():
    for user, password in read_admin_candidates():
        token = try_login(user, password)
        if token:
            return token, user
    return None, None


def provision_eval_user(admin_token, user, password):
    """管理员开户（公共注册保持关闭）；已存在时返回 False，由调用方决定回退。"""
    try:
        http_json("/auth/users", {"username": user, "password": password}, token=admin_token)
        return True
    except urllib.error.HTTPError as error:
        return error.code == 409
    except Exception:
        return False


def resolve_token():
    """优先使用隔离评测账号：缺失时由管理员开户；仍不可用则回退管理员并告警。"""
    user = os.environ.get("ASTER_EVAL_USER", "eval-bot").strip()
    password = os.environ.get("ASTER_EVAL_PASS", "eval-bot-local").strip()
    token = try_login(user, password)
    if token:
        return token, user
    admin_token, admin_user = admin_session()
    if not admin_token:
        sys.exit(f"无法登录本地后端（{BASE}）：请确认后端已启动且账号可用。")
    if provision_eval_user(admin_token, user, password):
        token = try_login(user, password)
        if token:
            print(f"ℹ 已由管理员开户并启用隔离评测账号 {user}")
            return token, user
        print(f"⚠ 评测账号 {user} 已存在但密码不可用；回退管理员账号。"
              "请设置 ASTER_EVAL_PASS 指向该账号密码。")
    else:
        print(f"⚠ 无法创建评测账号 {user}（可能已存在或权限不足），回退管理员账号；"
              "本次运行可能写入真实用户的会话数据。")
    return admin_token, admin_user


def sha256_of(path):
    try:
        with open(path, "rb") as handle:
            return hashlib.sha256(handle.read()).hexdigest()
    except OSError:
        return None


def git_commit():
    try:
        return subprocess.check_output(["git", "-C", ROOT, "rev-parse", "HEAD"],
                                       stderr=subprocess.DEVNULL).decode().strip()
    except Exception:
        return None


def redact(value):
    """递归移除凭据字段，manifest 可安全归档。"""
    if isinstance(value, dict):
        cleaned = {}
        for key, item in value.items():
            if any(word in key.lower() for word in ("secret", "password", "api_key", "apikey", "token")):
                cleaned[key] = "<redacted>" if item else item
            else:
                cleaned[key] = redact(item)
        return cleaned
    if isinstance(value, list):
        return [redact(item) for item in value]
    return value


def model_snapshot(token):
    try:
        return redact(http_json("/settings/models", token=token))
    except Exception as exception:
        return {"error": f"{type(exception).__name__}: {exception}"}


def build_manifest(token, eval_set, started_at):
    return {
        "backend": BASE,
        "git_commit": git_commit(),
        "eval_set": os.path.basename(EVAL_SET),
        "eval_set_sha256": sha256_of(EVAL_SET),
        "permission_mode": PERMISSION_MODE,
        "eval_user_isolated": os.environ.get("ASTER_EVAL_USER", "eval-bot"),
        "models": model_snapshot(token),
        "started_at": started_at,
    }


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
    return events, "".join(answer)


def open_stream(path_with_query, token):
    req = urllib.request.Request(BASE + path_with_query, headers={
        "Authorization": "Bearer " + token, "Accept": "text/event-stream"})
    return urllib.request.urlopen(req, timeout=60)


def save_artifact(result, case, events):
    """失败样本留证：轨迹 + SSE 原始事件 + 用例与检查明细，便于事后复盘。"""
    try:
        os.makedirs(ARTIFACT_DIR, exist_ok=True)
        directory = os.path.join(ARTIFACT_DIR, f"{time.strftime('%Y%m%d-%H%M%S')}-{case['id']}")
        os.makedirs(directory, exist_ok=True)
        token = result.pop("_token", None)
        conversation_id = result.get("conversation_id")
        run_id = result.get("run_id")
        if token and conversation_id and run_id:
            try:
                trajectory = http_json(
                    f"/ai/manus/conversations/{conversation_id}/runs/{run_id}/trajectory", token=token)
                with open(os.path.join(directory, "trajectory.json"), "w", encoding="utf-8") as handle:
                    json.dump(trajectory, handle, ensure_ascii=False, indent=2)
            except Exception as exception:
                result.setdefault("artifact_errors", []).append(f"trajectory: {exception}")
        with open(os.path.join(directory, "events.json"), "w", encoding="utf-8") as handle:
            json.dump(events, handle, ensure_ascii=False, indent=2)
        with open(os.path.join(directory, "meta.json"), "w", encoding="utf-8") as handle:
            json.dump({"case": case, "result": result}, handle, ensure_ascii=False, indent=2)
        result["artifact_dir"] = directory
        print(f"  📎 留证：{directory}")
    except Exception as exception:
        result.setdefault("errors", []).append(f"artifact: {exception}")


def run_case(case, token, deadline):
    started = time.time()
    conversation = http_json("/ai/manus/conversations", {}, token)
    conversation_id = conversation["id"]
    result = {"id": case["id"], "conversation_id": conversation_id, "events": 0,
              "plan_proposed": False, "answer": "", "plan": [], "checks": {},
              "latency_seconds": 0.0, "errors": [], "permission_mode": PERMISSION_MODE,
              "_token": token}
    events = []
    try:
        query = (f"/ai/manus/chat?conversationId={conversation_id}"
                 f"&message={urllib.parse.quote(case['task'])}"
                 f"&knowledgeSearch={str(case.get('knowledge', False)).lower()}"
                 f"&webSearch={str(case.get('web_search', False)).lower()}"
                 f"&permissionMode={PERMISSION_MODE}"
                 f"&clientRequestId=eval-{case['id']}-{int(started)}")
        with open_stream(query, token) as stream:
            events, answer = read_sse(stream, deadline)
        run_id = next((e["data"] for e in events if e["event"] == "run"
                       and len(e["data"]) < 64 and " " not in e["data"]), None)
        result["run_id"] = run_id
        result["plan_proposed"] = any(e["event"] == "plan-proposed" for e in events)
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
        passed, checks = evaluate(result, case)
        result["passed"] = passed
        result["checks"] = checks
        if not passed:
            save_artifact(result, case, events)
        result.pop("_token", None)
        try:
            http_json(f"/ai/manus/conversations/{conversation_id}", token=token, method="DELETE")
        except Exception:
            pass
    return result


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
    started_at = time.strftime("%Y-%m-%d %H:%M:%S")
    token, eval_user = resolve_token()
    manifest = build_manifest(token, eval_set, started_at)
    print(f"评测账号：{eval_user} · 权限模式：{PERMISSION_MODE} · commit：{(manifest.get('git_commit') or '')[:8]}")
    results = []
    for case in cases:
        print(f"▶ {case['id']}: {case['task'][:40]}…")
        result = run_case(case, token, time.time() + args.timeout)
        results.append(result)
        failed = [name for name, ok in result["checks"].items() if not ok]
        print(f"  {'✅' if result['passed'] else '❌'} {result['latency_seconds']}s"
              + (f" 未过：{', '.join(failed)}" if failed else ""))
    summary = {
        "generated_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        "manifest": manifest,
        "total": len(results),
        "passed": sum(1 for item in results if item["passed"]),
        "results": results,
    }
    summary = score_summary(summary, eval_set)
    with open(os.path.join(HERE, args.output), "w", encoding="utf-8") as handle:
        json.dump(summary, handle, ensure_ascii=False, indent=2)
    print(f"\n通过 {summary['passed']}/{summary['total']}，明细见 {args.output}")
    threshold = args.min_pass if args.min_pass is not None else summary["total"]
    sys.exit(0 if summary["passed"] >= threshold else 1)


if __name__ == "__main__":
    main()
