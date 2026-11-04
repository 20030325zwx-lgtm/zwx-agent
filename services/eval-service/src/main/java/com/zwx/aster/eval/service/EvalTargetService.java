package com.zwx.aster.eval.service;

import com.zwx.aster.eval.domain.CaseOutcome;
import com.zwx.aster.eval.domain.EvalCaseRecord;
import com.zwx.aster.eval.domain.EvalExperimentRecord;
import com.zwx.aster.eval.domain.EvalType;
import com.zwx.aster.eval.evaluator.EvalContext;
import com.zwx.aster.eval.evaluator.Evaluator;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 用例执行分派：按 evalType 选择评估器策略。
 * 异常统一转成失败结果（不打断实验），是否重试由 EvalRunner 决定。
 */
@Service
public class EvalTargetService {

    private final Map<EvalType, Evaluator> evaluators;

    public EvalTargetService(List<Evaluator> evaluators) {
        Map<EvalType, Evaluator> registry = new EnumMap<>(EvalType.class);
        evaluators.forEach(evaluator -> registry.put(evaluator.type(), evaluator));
        this.evaluators = registry;
    }

    public CaseOutcome execute(EvalExperimentRecord experiment, EvalCaseRecord caseRow) {
        Evaluator evaluator = evaluators.get(experiment.type());
        if (evaluator == null) {
            throw new IllegalStateException("没有可用的评估器：" + experiment.evalType());
        }
        return evaluator.evaluate(new EvalContext(
                experiment, caseRow, experiment.target(), experiment.budget()));
    }
}
