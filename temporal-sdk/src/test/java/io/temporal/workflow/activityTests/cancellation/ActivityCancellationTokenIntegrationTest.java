package io.temporal.workflow.activityTests.cancellation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assume.assumeTrue;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.MethodDescriptor;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityCancellationType;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.enums.v1.TaskQueueKind;
import io.temporal.api.workflowservice.v1.DescribeNamespaceRequest;
import io.temporal.api.workflowservice.v1.DescribeNamespaceResponse;
import io.temporal.api.workflowservice.v1.PollNexusTaskQueueRequest;
import io.temporal.client.ActivityCanceledException;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Async;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.Promise;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ActivityCancellationTokenIntegrationTest {

  private final List<PollNexusTaskQueueRequest> workerCommandPollRequests =
      new CopyOnWriteArrayList<>();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setTestTimeoutSeconds(30)
          .setWorkflowServiceStubsOptions(
              WorkflowServiceStubsOptions.newBuilder()
                  .addGrpcClientInterceptor(
                      new WorkerCommandPollRecordingInterceptor(workerCommandPollRequests))
                  .build())
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setWorkerHeartbeatInterval(Duration.ofSeconds(1))
                  .build())
          .setWorkflowTypes(TestCancellationWorkflowImpl.class)
          .setActivityImplementations(new NonHeartbeatingActivityImpl())
          .build();

  @Before
  public void checkServerSupportsWorkerCommands() {
    assumeTrue(
        "Requires real server with worker command support", SDKTestWorkflowRule.useExternalService);

    DescribeNamespaceResponse response =
        testWorkflowRule
            .getWorkflowClient()
            .getWorkflowServiceStubs()
            .blockingStub()
            .describeNamespace(
                DescribeNamespaceRequest.newBuilder()
                    .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
                    .build());
    assumeTrue(
        "Server does not support worker heartbeats",
        response.getNamespaceInfo().getCapabilities().getWorkerHeartbeats());
    assumeTrue(
        "Server does not support worker commands",
        response.getNamespaceInfo().getCapabilities().getWorkerCommands());
  }

  @Test
  @SuppressWarnings("deprecation")
  public void activityObservesCancellationWithoutHeartbeat() {
    TestCancellationWorkflow workflow =
        testWorkflowRule.newWorkflowStub(TestCancellationWorkflow.class);

    assertEquals("cancelled", workflow.execute(testWorkflowRule.getTaskQueue()));

    assertFalse("Expected a worker command Nexus poll", workerCommandPollRequests.isEmpty());
    for (PollNexusTaskQueueRequest request : workerCommandPollRequests) {
      assertFalse(request.hasDeploymentOptions());
      assertFalse(request.hasWorkerVersionCapabilities());
    }
  }

  private static class WorkerCommandPollRecordingInterceptor implements ClientInterceptor {
    private final List<PollNexusTaskQueueRequest> workerCommandPollRequests;

    private WorkerCommandPollRecordingInterceptor(
        List<PollNexusTaskQueueRequest> workerCommandPollRequests) {
      this.workerCommandPollRequests = workerCommandPollRequests;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
      return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
          next.newCall(method, callOptions)) {
        @Override
        public void sendMessage(ReqT message) {
          if (message instanceof PollNexusTaskQueueRequest) {
            PollNexusTaskQueueRequest request = (PollNexusTaskQueueRequest) message;
            if (request.getTaskQueue().getKind() == TaskQueueKind.TASK_QUEUE_KIND_WORKER_COMMANDS) {
              workerCommandPollRequests.add(request);
            }
          }
          super.sendMessage(message);
        }
      };
    }
  }

  @WorkflowInterface
  public interface TestCancellationWorkflow {
    @WorkflowMethod
    String execute(String taskQueue);

    @SignalMethod
    void activityStarted();
  }

  @ActivityInterface
  public interface NonHeartbeatingActivity {
    String waitForCancellation();
  }

  public static class TestCancellationWorkflowImpl implements TestCancellationWorkflow {
    private boolean activityStarted;

    @Override
    public String execute(String taskQueue) {
      NonHeartbeatingActivity activity =
          Workflow.newActivityStub(
              NonHeartbeatingActivity.class,
              ActivityOptions.newBuilder()
                  .setTaskQueue(taskQueue)
                  .setScheduleToCloseTimeout(Duration.ofSeconds(20))
                  .setStartToCloseTimeout(Duration.ofSeconds(20))
                  .setCancellationType(ActivityCancellationType.WAIT_CANCELLATION_COMPLETED)
                  .setDisableEagerExecution(true)
                  .build());

      List<Promise<String>> activityResults = new ArrayList<>();
      CancellationScope cancellationScope =
          Workflow.newCancellationScope(
              () -> activityResults.add(Async.function(activity::waitForCancellation)));

      cancellationScope.run();
      Workflow.await(() -> activityStarted);
      cancellationScope.cancel();

      try {
        activityResults.get(0).get();
        return "completed";
      } catch (ActivityFailure e) {
        if (e.getCause() instanceof CanceledFailure) {
          return "cancelled";
        }
        throw e;
      }
    }

    @Override
    public void activityStarted() {
      activityStarted = true;
    }
  }

  public static class NonHeartbeatingActivityImpl implements NonHeartbeatingActivity {
    @Override
    public String waitForCancellation() {
      ActivityExecutionContext context = Activity.getExecutionContext();
      context
          .getWorkflowClient()
          .newWorkflowStub(TestCancellationWorkflow.class, context.getInfo().getWorkflowId())
          .activityStarted();

      try {
        context.getCancellationToken().getCancellationFuture().get(20, TimeUnit.SECONDS);
        context.getCancellationToken().throwIfCancellationRequested();
        return "not-cancelled";
      } catch (ActivityCanceledException e) {
        throw e;
      } catch (ExecutionException e) {
        if (e.getCause() instanceof ActivityCanceledException) {
          throw (ActivityCanceledException) e.getCause();
        }
        throw new RuntimeException(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      } catch (TimeoutException e) {
        throw new RuntimeException(e);
      }
    }
  }
}
