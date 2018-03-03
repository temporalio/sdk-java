package com.uber.cadence.internal.sync;

import com.uber.cadence.internal.replay.DecisionContext;
import com.uber.cadence.workflow.WorkflowInfo;

import java.time.Duration;

final class WorkflowInfoImpl implements WorkflowInfo {
    private final DecisionContext context;

    WorkflowInfoImpl(DecisionContext context) {
        this.context = context;
    }


    @Override
    public String getDomain() {
        return context.getDomain();
    }

    @Override
    public String getWorkflowId() {
        return context.getWorkflowId();
    }

    @Override
    public String getRunId() {
        return context.getRunId();
    }

    @Override
    public String getWorkflowType() {
        return context.getWorkflowType().getName();
    }

    @Override
    public String getTaskList() {
        return getTaskList();
    }

    @Override
    public Duration getExecutionStartToCloseTimeout() {
        return context.getExecutionStartToCloseTimeout();
    }
}
