package io.temporal.workflow;

import static org.junit.Assert.assertEquals;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.Priority;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class PriorityInfoTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestPriority.class, TestPriorityChildWorkflow.class)
          .setActivityImplementations(new PriorityActivitiesImpl())
          .build();

  @Test
  public void testPriority() {
    TestWorkflow1 workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflow1.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .setPriority(
                        Priority.newBuilder()
                            .setPriorityKey(5)
                            .setFairnessKey("tenant-123")
                            .setFairnessWeight(2.5f)
                            .build())
                    .build());
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    assertEquals("5:tenant-123:2.5", result);
  }

  @ActivityInterface
  public interface PriorityActivities {
    String activity1(String a1);
  }

  public static class PriorityActivitiesImpl implements PriorityActivities {
    @Override
    public String activity1(String a1) {
      Priority priority = Activity.getExecutionContext().getInfo().getPriority();
      String key = priority.getFairnessKey() != null ? priority.getFairnessKey() : "null";
      return priority.getPriorityKey() + ":" + key + ":" + priority.getFairnessWeight();
    }
  }

  public static class TestPriorityChildWorkflow implements TestWorkflows.TestWorkflowReturnString {
    @Override
    public String execute() {
      Priority priority = Workflow.getInfo().getPriority();
      String key = priority.getFairnessKey() != null ? priority.getFairnessKey() : "null";
      return priority.getPriorityKey() + ":" + key + ":" + priority.getFairnessWeight();
    }
  }

  public static class TestPriority implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      // Test that the priority is passed to activities
      String priority =
          Workflow.newActivityStub(
                  PriorityActivities.class,
                  ActivityOptions.newBuilder()
                      .setTaskQueue(taskQueue)
                      .setStartToCloseTimeout(Duration.ofSeconds(10))
                      .setPriority(
                          Priority.newBuilder()
                              .setPriorityKey(3)
                              .setFairnessKey("override")
                              .setFairnessWeight(1.5f)
                              .build())
                      .setDisableEagerExecution(true)
                      .build())
              .activity1("1");
      Assert.assertEquals("3:override:1.5", priority);
      // Test that if no priority is set the workflow's priority is used
      priority =
          Workflow.newActivityStub(
                  PriorityActivities.class,
                  ActivityOptions.newBuilder()
                      .setTaskQueue(taskQueue)
                      .setStartToCloseTimeout(Duration.ofSeconds(10))
                      .setDisableEagerExecution(true)
                      .build())
              .activity1("2");
      Assert.assertEquals("5:tenant-123:2.5", priority);
      // Test that the priority is passed to child workflows
      priority =
          Workflow.newChildWorkflowStub(
                  TestWorkflows.TestWorkflowReturnString.class,
                  ChildWorkflowOptions.newBuilder()
                      .setPriority(
                          Priority.newBuilder()
                              .setPriorityKey(1)
                              .setFairnessKey("child")
                              .setFairnessWeight(0.5f)
                              .build())
                      .build())
              .execute();
      Assert.assertEquals("1:child:0.5", priority);
      // Test that if no priority is set the workflow's priority is used
      priority =
          Workflow.newChildWorkflowStub(
                  TestWorkflows.TestWorkflowReturnString.class,
                  ChildWorkflowOptions.newBuilder().build())
              .execute();
      Assert.assertEquals("5:tenant-123:2.5", priority);
      // Return the workflow's priority
      Priority workflowPriority = Workflow.getInfo().getPriority();
      String key =
          workflowPriority.getFairnessKey() != null ? workflowPriority.getFairnessKey() : "null";
      return workflowPriority.getPriorityKey()
          + ":"
          + key
          + ":"
          + workflowPriority.getFairnessWeight();
    }
  }
}
