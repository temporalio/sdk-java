package io.temporal.spring.boot.autoconfigure.byworkername;

import io.temporal.common.converter.EncodedValues;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.DynamicWorkflow;

@WorkflowImpl(workers = "mainWorker")
public class TestDynamicWorkflowImpl implements DynamicWorkflow {
  @Override
  public Object execute(EncodedValues args) {
    return "hello from dynamic workflow";
  }
}
