package io.temporal.spring.boot.autoconfigure.byworkernameresolver;

import io.temporal.activity.ActivityOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import org.springframework.context.annotation.Profile;

@WorkflowImpl(workers = "${worker.name}")
@Profile("auto-discovery-by-worker-name-resolver")
public class TestWorkflowImpl implements TestWorkflow {

  @Override
  public String execute(String input) {
    return Workflow.newActivityStub(
            TestActivity.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(1))
                .validateAndBuildWithDefaults())
        .execute(input);
  }
}
