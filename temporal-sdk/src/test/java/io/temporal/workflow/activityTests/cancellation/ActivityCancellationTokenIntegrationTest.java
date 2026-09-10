package io.temporal.workflow.activityTests.cancellation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
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
import io.temporal.common.WorkerDeploymentVersion;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.CloudTestExclusion.NeedsCloudAdaptation;
import io.temporal.testing.CloudTestExclusionNote;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerDeploymentOptions;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.Async;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.Promise;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.shared.EchoNexusServiceImpl;
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
import org.junit.experimental.categories.Category;

@CloudTestExclusionNote(
    "Requires test-specific gRPC interception and worker heartbeat configuration in addition to envconfig options.")
@Category(NeedsCloudAdaptation.class)
public class ActivityCancellationTokenIntegrationTest {

  private final List<PollNexusTaskQueueRequest> workerCommandPollRequests =
      new CopyOnWriteArrayList<>();
  private final List<PollNexusTaskQueueRequest> normalNexusPollRequests =
      new CopyOnWriteArrayList<>();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setTestTimeoutSeconds(30)
          .setWorkflowServiceStubsOptions(
              WorkflowServiceStubsOptions.newBuilder()
                  .addGrpcClientInterceptor(
                      new NexusPollRecordingInterceptor(
                          workerCommandPollRequests, normalNexusPollRequests))
                  .build())
          // Configure deployment options without useVersioning(true). This causes the SDK to
          // send deploymentOptions on poll requests (with UNVERSIONED mode) without requiring
          // server-side deployment setup. This is sufficient to verify that worker-command polls
          // omit deploymentOptions while normal polls include them — the field presence is the
          // same regardless of versioning mode.
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setDeploymentOptions(
                      WorkerDeploymentOptions.newBuilder()
                          .setVersion(
                              new WorkerDeploymentVersion("test-deployment", "test-build-id"))
                          .build())
                  .build())
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setWorkerHeartbeatInterval(Duration.ofSeconds(1))
                  .build())
          .setWorkflowTypes(TestCancellationWorkflowImpl.class)
          .setActivityImplementations(new NonHeartbeatingActivityImpl())
          .setNexusServiceImplementation(new EchoNexusServiceImpl())
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

    // Normal nexus polls must carry deployment options (positive control).
    assertFalse("Expected at least one normal Nexus poll", normalNexusPollRequests.isEmpty());
    for (PollNexusTaskQueueRequest request : normalNexusPollRequests) {
      assertTrue(
          "Normal nexus poll should have deployment options", request.hasDeploymentOptions());
    }

    // Worker command polls must NOT carry any versioning metadata.
    assertFalse(
        "Expected at least one worker command Nexus poll", workerCommandPollRequests.isEmpty());
    for (PollNexusTaskQueueRequest request : workerCommandPollRequests) {
      assertFalse(
          "Worker command poll should not have deployment options", request.hasDeploymentOptions());
      assertFalse(
          "Worker command poll should not have worker version capabilities",
          request.hasWorkerVersionCapabilities());
    }
  }

  private static class NexusPollRecordingInterceptor implements ClientInterceptor {
    private final List<PollNexusTaskQueueRequest> workerCommandPollRequests;
    private final List<PollNexusTaskQueueRequest> normalNexusPollRequests;

    private NexusPollRecordingInterceptor(
        List<PollNexusTaskQueueRequest> workerCommandPollRequests,
        List<PollNexusTaskQueueRequest> normalNexusPollRequests) {
      this.workerCommandPollRequests = workerCommandPollRequests;
      this.normalNexusPollRequests = normalNexusPollRequests;
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
            } else if (request.getTaskQueue().getKind() == TaskQueueKind.TASK_QUEUE_KIND_NORMAL) {
              normalNexusPollRequests.add(request);
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
