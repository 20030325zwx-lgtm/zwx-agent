package com.zwx.aster.eval.evaluator;

import com.zwx.aster.eval.domain.CaseOutcome;
import com.zwx.aster.eval.domain.EvalType;

/**
 * 评估器策略：一个 evalType 一个实现。新增评估器只需加一个 Bean，
 * 调度/存储/报告不需要改（对照 promptfoo 的 provider 与 DeepEval 的 metric 分层）。
 */
public interface Evaluator {

    EvalType type();

    CaseOutcome evaluate(EvalContext context);
}
