package io.temporal.worker;

import static io.temporal.testUtils.Eventually.assertEventually;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import io.temporal.api.worker.v1.WorkerHeartbeat;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.WorkerDeploymentVersion;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class WorkerHeartbeatDeploymentVersionTest {

  private static final Duration EVENTUALLY_TIMEOUT = Duration.ofSeconds(10);

  private static final String TEST_DEPLOYMENT_NAME = "test-deployment";
  private static final String TEST_BUILD_ID = "1.0.0";

  @Before
  public void checkServerSupportsHeartbeats() {
    assumeTrue(
        "Requires real server with worker heartbeat support",
        SDKTestWorkflowRule.useExternalService);
    assumeTrue(
        "Server does not support worker heartbeats",
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
            .getWorkerHeartbeats());
  }

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setUseExternalService(true)
          .setTestTimeoutSeconds(15)
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setWorkerHeartbeatInterval(Duration.ofSeconds(1))
                  .build())
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setDeploymentOptions(
                      WorkerDeploymentOptions.newBuilder()
                          .setVersion(
                              new WorkerDeploymentVersion(TEST_DEPLOYMENT_NAME, TEST_BUILD_ID))
                          .build())
                  .build())
          .setDoNotStart(true)
          .build();

  @Test
  public void testDeploymentVersionInHeartbeat() throws Exception {
    testWorkflowRule.getTestEnvironment().start();

    String taskQueue = testWorkflowRule.getTaskQueue();

    // Discover the worker via ListWorkers, then verify deployment version via DescribeWorker
    assertEventually(
        EVENTUALLY_TIMEOUT,
        () -> {
          List<WorkerHeartbeat> workers = listWorkersForQueue(taskQueue);
          assertFalse("worker should appear via ListWorkers", workers.isEmpty());

          WorkerHeartbeat hb = describeWorker(workers.get(0).getWorkerInstanceKey());
          assertNotNull("DescribeWorker should return stored heartbeat", hb);
          assertTrue("deployment_version should be set", hb.hasDeploymentVersion());
          assertEquals(
              "deployment_version.deployment_name should match configured value",
              TEST_DEPLOYMENT_NAME,
              hb.getDeploymentVersion().getDeploymentName());
          assertEquals(
              "deployment_version.build_id should match configured value",
              TEST_BUILD_ID,
              hb.getDeploymentVersion().getBuildId());
        });
  }

  /**
   * Uses deprecated WorkersInfo field because the replacement is not yet populated by the server.
   */
  @SuppressWarnings("deprecation")
  private List<WorkerHeartbeat> listWorkersForQueue(String taskQueue) {
    ListWorkersResponse resp =
        testWorkflowRule
            .getWorkflowClient()
            .getWorkflowServiceStubs()
            .blockingStub()
            .listWorkers(
                ListWorkersRequest.newBuilder()
                    .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
                    .setPageSize(100)
                    .build());
    return resp.getWorkersInfoList().stream()
        .map(info -> info.getWorkerHeartbeat())
        .filter(hb -> hb.getTaskQueue().equals(taskQueue))
        .collect(Collectors.toList());
  }

  private WorkerHeartbeat describeWorker(String workerInstanceKey) {
    try {
      DescribeWorkerResponse resp =
          testWorkflowRule
              .getWorkflowClient()
              .getWorkflowServiceStubs()
              .blockingStub()
              .describeWorker(
                  DescribeWorkerRequest.newBuilder()
                      .setNamespace(
                          testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
                      .setWorkerInstanceKey(workerInstanceKey)
                      .build());
      return resp.getWorkerInfo().getWorkerHeartbeat();
    } catch (io.grpc.StatusRuntimeException e) {
      if (e.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND) {
        return null;
      }
      throw e;
    }
  }
}
