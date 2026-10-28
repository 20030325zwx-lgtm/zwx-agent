#!/usr/bin/env python3
"""任务级评测的独立评分器：只读取结果 JSON，不触发任何模型或后端调用。

职责分离：
  - run_task_eval.py 负责“执行 + 留证”（真实模型、SSE、轨迹导出）；
  - 本脚本负责“评分”，可对历史结果 JSON 独立重放与重算，便于评测集/断言调整后回溯对比。

用法：
  python3 score_task_result.py task-eval-result.json            # 重算并打印
  python3 score_task_result.py task-eval-result.json --out scored.json
"""
import argparse
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_EVAL_SET = os.path.join(HERE, "task-eval-v1.json")
FILE_PATTERN = re.compile(r"[\w\u4e00-\u9fa5.-]+\.(md|pdf|txt|docx|xlsx|csv|html)", re.I)


def evaluate(result, case):
    """按用例期望核对单个结果；返回 (是否通过, 检查明细)。"""
    checks = {}
    expect = case.get("expect", {})
    checks["answer_non_empty"] = bool((result.get("answer") or "").strip())
    plan = result.get("plan") or []
    if expect.get("casual_direct"):
        checks["no_plan"] = not plan and not result.get("plan_proposed")
    if "min_plan_steps" in expect:
        checks["min_plan_steps"] = len(plan) >= expect["min_plan_steps"]
    if "max_plan_steps" in expect:
        checks["max_plan_steps"] = len(plan) <= expect["max_plan_steps"]
    if expect.get("has_dependencies"):
        checks["has_dependencies"] = any(step.get("dependsOn") for step in plan)
    if expect.get("expect_file"):
        haystack = (result.get("answer") or "") + json.dumps(plan, ensure_ascii=False)
        checks["file_mentioned"] = bool(FILE_PATTERN.search(haystack))
    if expect.get("verification") == "pass":
        checks["verification_pass"] = result.get("verification") == "pass"
    # 显式权限模式（YOLO）下不应再出现计划确认门
    if result.get("permission_mode"):
        checks["no_unexpected_plan_gate"] = not result.get("plan_proposed") or \
            "casual" in str(case.get("id", ""))
    checks["no_errors"] = not result.get("errors")
    return all(checks.values()), checks


def score_summary(summary, eval_set):
    cases = {case["id"]: case for case in eval_set.get("cases", [])}
    for result in summary.get("results", []):
        case = cases.get(result.get("id"))
        if case is None:
            result["passed"] = False
            result["checks"] = {"unknown_case": False}
            continue
        passed, checks = evaluate(result, case)
        result["passed"] = passed
        result["checks"] = checks
    results = summary.get("results", [])
    summary["total"] = len(results)
    summary["passed"] = sum(1 for item in results if item.get("passed"))
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", help="run_task_eval.py 产出的结果 JSON")
    parser.add_argument("--eval-set", default=DEFAULT_EVAL_SET)
    parser.add_argument("--out", default=None, help="另存重算后的结果；缺省只打印")
    args = parser.parse_args()
    with open(args.eval_set, encoding="utf-8") as handle:
        eval_set = json.load(handle)
    with open(args.input, encoding="utf-8") as handle:
        summary = json.load(handle)
    summary = score_summary(summary, eval_set)
    for result in summary.get("results", []):
        failed = [name for name, ok in result.get("checks", {}).items() if not ok]
        print(f"{'✅' if result.get('passed') else '❌'} {result.get('id')}"
              + (f"  未过：{', '.join(failed)}" if failed else ""))
    print(f"\n通过 {summary['passed']}/{summary['total']}")
    if args.out:
        with open(args.out, "w", encoding="utf-8") as handle:
            json.dump(summary, handle, ensure_ascii=False, indent=2)
        print(f"明细已写入 {args.out}")
    sys.exit(0 if summary["passed"] == summary["total"] else 1)


if __name__ == "__main__":
    main()
