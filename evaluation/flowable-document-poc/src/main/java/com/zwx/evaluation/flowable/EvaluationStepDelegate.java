package com.zwx.evaluation.flowable;

import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;

public class EvaluationStepDelegate implements JavaDelegate {
    @Override
    public void execute(DelegateExecution execution) {
        String step = (String) execution.getCurrentActivityId();
        execution.setVariable("lastCompletedStep", step);
    }
}
