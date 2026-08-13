package io.temporal.worker;

import static io.temporal.testUtils.Eventually.assertEventually;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.MethodDescriptor;
import io.temporal.api.enums.v1.TaskQueueKind;
import io.temporal.api.workflowservice.v1.DescribeNamespaceRequest;
import io.temporal.api.workflowservice.v1.PollNexusTaskQueueRequest;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.WorkerDeploymentVersion;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.EchoNexusServiceImpl;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Verifies that nexus poll requests for worker-command task queues do not carry versioning
 * metadata, even when the worker is configured with deployment options.
 */
public class WorkerCommandNexusPollVersioningTest {

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
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setWorkerHeartbeatInterval(Duration.ofSeconds(1))
                  .build())
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setDeploymentOptions(
                      WorkerDeploymentOptions.newBuilder()
                          .setVersion(
                              new WorkerDeploymentVersion("test-deployment", "test-build-id"))
                          .build())
                  .build())
          .setNexusServiceImplementation(new EchoNexusServiceImpl())
          .setDoNotStart(true)
          .build();

  @Before
  public void checkServerSupportsWorkerCommands() {
    assumeTrue(
        "Requires real server with worker command support", SDKTestWorkflowRule.useExternalService);
    assumeTrue(
        "Server does not support worker commands",
        testWorkflowRule
            .getWorkflowClient()
            .getWorkflowServiceStubs()
            .blockingStub()
            .describeNamespace(
                DescribeNamespaceRequest.newBuilder()
                    .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
                    .build())
            .getNamespaceInfo()
            .getCapabilities()
            .getWorkerCommands());
  }

  @Test
  @SuppressWarnings("deprecation")
  public void workerCommandPollsOmitVersioningMetadata() {
    testWorkflowRule.getTestEnvironment().start();

    // Wait until we've captured at least one poll of each kind.
    assertEventually(
        Duration.ofSeconds(10),
        () -> {
          assertFalse("Expected at least one normal Nexus poll", normalNexusPollRequests.isEmpty());
          assertFalse(
              "Expected at least one worker command Nexus poll",
              workerCommandPollRequests.isEmpty());
        });

    // Normal nexus polls must carry deployment options.
    for (PollNexusTaskQueueRequest request : normalNexusPollRequests) {
      assertTrue(
          "Normal nexus poll should have deployment options", request.hasDeploymentOptions());
    }

    // Worker command polls must NOT carry any versioning metadata.
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
}
