package io.temporal.spring.boot.autoconfigure.byworkername;

import io.temporal.activity.ActivityOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;

@WorkflowImpl(workers = "mainWorker")
@Profile("auto-discovery-with-profile")
public class OtherTestWorkflowImpl implements TestWorkflow {

  // Test auto-wiring of the application context works, this is not indicative of a real-world use
  // case as the workflow implementation should be stateless.
  public OtherTestWorkflowImpl(ConfigurableApplicationContext applicationContext) {}

  @Override
  public String execute(String input) {
    return Workflow.newActivityStub(
            TestActivity.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(1))
                .validateAndBuildWithDefaults())
        .execute("discovered");
  }
}
