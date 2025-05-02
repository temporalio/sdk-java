package io.temporal.workflow.shared;

import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.Optional;

public class TestNoArgsWorkflowFuncParent
    implements TestMultiArgWorkflowFunctions.TestNoArgsWorkflowFunc {
  @Override
  public String func() {
    ChildWorkflowOptions workflowOptions =
        ChildWorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(100))
            .setWorkflowTaskTimeout(Duration.ofSeconds(60))
            .build();
    TestMultiArgWorkflowFunctions.Test2ArgWorkflowFunc child =
        Workflow.newChildWorkflowStub(
            TestMultiArgWorkflowFunctions.Test2ArgWorkflowFunc.class, workflowOptions);

    Optional<String> parentWorkflowId = Workflow.getInfo().getParentWorkflowId();
    String childsParentWorkflowId = child.func2(null, 0);

    String result = String.format("%s - %s", parentWorkflowId.isPresent(), childsParentWorkflowId);
    return result;
  }

  @Override
  public String update() {
    throw new UnsupportedOperationException();
  }
}
