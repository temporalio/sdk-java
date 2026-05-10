package io.temporal.spring.boot.autoconfigure.byenable;

import io.temporal.activity.ActivityOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * A workflow implementation that is a Spring bean (has {@code @Component}), used to verify that
 * {@code workers-auto-discovery.enabled: true} can discover workflow beans without classpath
 * scanning.
 */
@Component
@Profile("auto-discovery-enable")
@WorkflowImpl(workers = "mainWorker")
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
