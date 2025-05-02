package io.temporal.spring.boot.autoconfigure.byworkername;

import io.temporal.activity.ActivityOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.NexusOperationOptions;
import io.temporal.workflow.NexusServiceOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import org.springframework.context.ConfigurableApplicationContext;

@WorkflowImpl(workers = "mainWorker")
public class TestWorkflowImpl implements TestWorkflow {

  // Test auto-wiring of the application context works, this is not indicative of a real-world use
  // case as the workflow implementation should be stateless.
  public TestWorkflowImpl(ConfigurableApplicationContext applicationContext) {}

  @Override
  public String execute(String input) {
    if (input.equals("nexus")) {
      Workflow.newNexusServiceStub(
              TestNexusService.class,
              NexusServiceOptions.newBuilder()
                  .setEndpoint("AutoDiscoveryByWorkerNameTestEndpoint")
                  .setOperationOptions(
                      NexusOperationOptions.newBuilder()
                          .setScheduleToCloseTimeout(Duration.ofSeconds(10))
                          .build())
                  .build())
          .operation(input);
    }
    return Workflow.newActivityStub(
            TestActivity.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(1))
                .validateAndBuildWithDefaults())
        .execute("done");
  }
}
