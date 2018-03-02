package com.uber.cadence.workflow;

import java.time.Duration;

public interface WorkflowInfo {

    String getDomain();

    String getWorkflowId();

    String getRunId();

    String getWorkflowType();

    String getTaskList();

    Duration getExecutionStartToCloseTimeout();
}
