package io.temporal.spring.boot.autoconfigure.bytaskqueue;

import io.temporal.activity.ActivityOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.spring.boot.autoconfigure.byworkername.TestNexusService;
import io.temporal.workflow.NexusOperationOptions;
import io.temporal.workflow.NexusServiceOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInit;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;

@WorkflowImpl(taskQueues = {"${default-queue.name:UnitTest}"})
public class TestWorkflowImpl implements TestWorkflow {
  private final TestNexusService nexusService;
  private final TestActivity activity;

  @Autowired private ConfigurableApplicationContext applicationContext;

  @WorkflowInit
  public TestWorkflowImpl(String input) {
    nexusService =
        Workflow.newNexusServiceStub(
            TestNexusService.class,
            NexusServiceOptions.newBuilder()
                .setEndpoint("AutoDiscoveryByTaskQueueEndpoint")
                .setOperationOptions(
                    NexusOperationOptions.newBuilder()
                        .setScheduleToCloseTimeout(Duration.ofSeconds(10))
                        .build())
                .build());

    activity =
        Workflow.newActivityStub(
            TestActivity.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(1))
                .validateAndBuildWithDefaults());
  }

  @Override
  public String execute(String input) {
    if (input.equals("nexus")) {
      nexusService.operation(input);
    }
    return activity.execute("done");
  }
}
