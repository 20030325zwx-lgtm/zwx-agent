package com.zwx.aster.eval.evaluator;

import com.zwx.aster.eval.domain.EvalCaseRecord;
import com.zwx.aster.eval.domain.EvalExperimentRecord;
import com.zwx.aster.eval.domain.RunBudget;
import com.zwx.aster.eval.domain.TargetView;

/** 单例评测的输入：实验（含冻结的被测对象快照）、用例、预算。 */
public record EvalContext(EvalExperimentRecord experiment,
                          EvalCaseRecord caseRow,
                          TargetView target,
                          RunBudget budget) {
}
