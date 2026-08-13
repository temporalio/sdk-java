package io.temporal.workflow.nexus;

import static org.junit.Assume.assumeTrue;

import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.api.enums.v1.ActivityIdConflictPolicy;
import io.temporal.client.StartActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.nexus.TemporalOperationHandler;
import io.temporal.testUtils.Eventually;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.NexusOperationExecution;
import io.temporal.workflow.NexusOperationHandle;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.shared.TestNexusServices;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ActivityHandleUseExistingOnConflictTest {
  private static final int OPERATION_COUNT = 5;

  private final CountDownLatch activityStarted = new CountDownLatch(1);
  private final CountDownLatch releaseActivity = new CountDownLatch(1);
  private final AtomicInteger activityInvocationCount = new AtomicInteger();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestNexus.class)
          .setActivityImplementations(new BlockingActivityImpl())
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  @Test
  public void testOnConflictUseExisting() throws Exception {
    // The in-process test server does not implement the StartActivityExecution RPC.
    assumeTrue(SDKTestWorkflowRule.useExternalService);

    TestWorkflow workflowStub = testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow.class);
    String activityId = "activity-" + UUID.randomUUID();
    WorkflowClient.start(workflowStub::execute, activityId);

    try {
      Assert.assertTrue(
          "The standalone activity should start", activityStarted.await(20, TimeUnit.SECONDS));
      Eventually.assertEventually(
          Duration.ofSeconds(20),
          () ->
              Assert.assertTrue(
                  "All Nexus operations should attach before completion",
                  workflowStub.allOperationsStarted()));
    } finally {
      releaseActivity.countDown();
    }

    Assert.assertEquals(
        "completed " + activityId,
        WorkflowStub.fromTyped(workflowStub).getResult(30, TimeUnit.SECONDS, String.class));
    Assert.assertEquals(1, activityInvocationCount.get());
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    String execute(String activityId);

    @QueryMethod
    boolean allOperationsStarted();
  }

  public static class TestNexus implements TestWorkflow {
    private boolean allOperationsStarted;

    @Override
    public String execute(String activityId) {
      TestNexusServices.TestNexusService1 serviceStub =
          Workflow.newNexusServiceStub(TestNexusServices.TestNexusService1.class);
      List<NexusOperationHandle<String>> handles = new ArrayList<>();
      for (int i = 0; i < OPERATION_COUNT; i++) {
        handles.add(Workflow.startNexusOperation(serviceStub::operation, activityId));
      }

      String operationToken = null;
      for (NexusOperationHandle<String> handle : handles) {
        NexusOperationExecution execution = handle.getExecution().get();
        Assert.assertTrue(execution.getOperationToken().isPresent());
        if (operationToken == null) {
          operationToken = execution.getOperationToken().get();
        } else {
          Assert.assertEquals(operationToken, execution.getOperationToken().get());
        }
      }
      allOperationsStarted = true;

      String result = null;
      for (NexusOperationHandle<String> handle : handles) {
        String currentResult = handle.getResult().get();
        if (result == null) {
          result = currentResult;
        } else {
          Assert.assertEquals(result, currentResult);
        }
      }
      return result;
    }

    @Override
    public boolean allOperationsStarted() {
      return allOperationsStarted;
    }
  }

  @ActivityInterface
  public interface BlockingActivity {
    @ActivityMethod
    String execute(String activityId);
  }

  public class BlockingActivityImpl implements BlockingActivity {
    @Override
    public String execute(String activityId) {
      activityInvocationCount.incrementAndGet();
      activityStarted.countDown();
      try {
        releaseActivity.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
      return "completed " + activityId;
    }
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return TemporalOperationHandler.create(
          (context, client, activityId) ->
              client.startActivity(
                  BlockingActivity.class,
                  BlockingActivity::execute,
                  activityId,
                  StartActivityOptions.newBuilder()
                      .setId(activityId)
                      .setTaskQueue(testWorkflowRule.getTaskQueue())
                      .setScheduleToCloseTimeout(Duration.ofMinutes(1))
                      .setIdConflictPolicy(
                          ActivityIdConflictPolicy.ACTIVITY_ID_CONFLICT_POLICY_USE_EXISTING)
                      .build()));
    }
  }
}
