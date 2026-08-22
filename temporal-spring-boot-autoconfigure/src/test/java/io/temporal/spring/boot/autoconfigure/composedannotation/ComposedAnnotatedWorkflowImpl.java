package io.temporal.spring.boot.autoconfigure.composedannotation;

import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;

@ComposedWorkflowImpl
public class ComposedAnnotatedWorkflowImpl implements ComposedAnnotatedWorkflow {

  @Override
  public String execute(String input) {
    ComposedAnnotatedActivity activity =
        Workflow.newActivityStub(
            ComposedAnnotatedActivity.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .validateAndBuildWithDefaults());
    return "composed:" + activity.execute(input);
  }
}
