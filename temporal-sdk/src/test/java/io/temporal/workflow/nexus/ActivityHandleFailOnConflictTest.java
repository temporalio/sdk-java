package io.temporal.workflow.nexus;

import static org.junit.Assume.assumeTrue;

import io.nexusrpc.handler.HandlerException;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.api.enums.v1.ActivityIdConflictPolicy;
import io.temporal.client.ActivityClient;
import io.temporal.client.ActivityClientOptions;
import io.temporal.client.ActivityHandle;
import io.temporal.client.StartActivityOptions;
import io.temporal.client.WorkflowFailedException;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.NexusOperationFailure;
import io.temporal.nexus.TemporalOperationHandler;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ActivityHandleFailOnConflictTest {
  private final CountDownLatch activityStarted = new CountDownLatch(1);
  private final CountDownLatch releaseActivity = new CountDownLatch(1);
  private final AtomicInteger nexusInvocationCount = new AtomicInteger();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestNexus.class)
          .setActivityImplementations(new BlockingActivityImpl())
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  @Test
  public void activityAlreadyStartedIsBadRequest() throws Exception {
    // The in-process test server does not implement the StartActivityExecution RPC.
    assumeTrue(SDKTestWorkflowRule.useExternalService);

    String activityId = "activity-" + UUID.randomUUID();
    StartActivityOptions activityOptions =
        StartActivityOptions.newBuilder()
            .setId(activityId)
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setScheduleToCloseTimeout(Duration.ofMinutes(1))
            .setIdConflictPolicy(ActivityIdConflictPolicy.ACTIVITY_ID_CONFLICT_POLICY_FAIL)
            .build();
    ActivityClient activityClient =
        ActivityClient.newInstance(
            testWorkflowRule.getWorkflowServiceStubs(),
            ActivityClientOptions.newBuilder().setNamespace(SDKTestWorkflowRule.NAMESPACE).build());
    ActivityHandle<String> runningActivity =
        activityClient.start(
            BlockingActivity.class, BlockingActivity::execute, activityOptions, activityId);

    try {
      Assert.assertTrue(
          "The standalone activity should start", activityStarted.await(20, TimeUnit.SECONDS));

      TestWorkflows.TestWorkflow1 workflowStub =
          testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
      WorkflowFailedException workflowFailure =
          Assert.assertThrows(
              WorkflowFailedException.class, () -> workflowStub.execute(activityId));

      Assert.assertTrue(workflowFailure.getCause() instanceof NexusOperationFailure);
      NexusOperationFailure nexusFailure = (NexusOperationFailure) workflowFailure.getCause();
      Assert.assertTrue(nexusFailure.getCause() instanceof HandlerException);
      HandlerException handlerFailure = (HandlerException) nexusFailure.getCause();
      Assert.assertEquals(HandlerException.ErrorType.BAD_REQUEST, handlerFailure.getErrorType());
      Assert.assertFalse(handlerFailure.isRetryable());
      Assert.assertTrue(handlerFailure.getCause() instanceof ApplicationFailure);
      Assert.assertEquals(
          "io.temporal.client.ActivityAlreadyStartedException",
          ((ApplicationFailure) handlerFailure.getCause()).getType());
      Assert.assertEquals(1, nexusInvocationCount.get());
    } finally {
      releaseActivity.countDown();
      runningActivity.getResult(30, TimeUnit.SECONDS);
    }
  }

  public static class TestNexus implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String activityId) {
      TestNexusServices.TestNexusService1 serviceStub =
          Workflow.newNexusServiceStub(TestNexusServices.TestNexusService1.class);
      return serviceStub.operation(activityId);
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
      activityStarted.countDown();
      try {
        releaseActivity.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
      return activityId;
    }
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return TemporalOperationHandler.create(
          (context, client, activityId) -> {
            nexusInvocationCount.incrementAndGet();
            return client.startActivity(
                BlockingActivity.class,
                BlockingActivity::execute,
                activityId,
                StartActivityOptions.newBuilder()
                    .setId(activityId)
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .setScheduleToCloseTimeout(Duration.ofMinutes(1))
                    .setIdConflictPolicy(ActivityIdConflictPolicy.ACTIVITY_ID_CONFLICT_POLICY_FAIL)
                    .build());
          });
    }
  }
}
