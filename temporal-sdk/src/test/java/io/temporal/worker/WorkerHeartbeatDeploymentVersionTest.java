package io.temporal.worker;

import static org.junit.Assert.*;

import io.temporal.api.workflowservice.v1.RecordWorkerHeartbeatRequest;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.WorkerDeploymentVersion;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;

public class WorkerHeartbeatDeploymentVersionTest {

  private static final HeartbeatCapturingInterceptor interceptor =
      new HeartbeatCapturingInterceptor();

  private static final String TEST_DEPLOYMENT_NAME = "test-deployment";
  private static final String TEST_BUILD_ID = "1.0.0";

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowServiceStubsOptions(
              WorkflowServiceStubsOptions.newBuilder()
                  .setGrpcClientInterceptors(Collections.singletonList(interceptor))
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
                              new WorkerDeploymentVersion(TEST_DEPLOYMENT_NAME, TEST_BUILD_ID))
                          .build())
                  .build())
          .setDoNotStart(true)
          .build();

  @Test
  public void testDeploymentVersionInHeartbeat() throws Exception {
    interceptor.clear();
    testWorkflowRule.getTestEnvironment().start();

    Thread.sleep(3000);

    List<RecordWorkerHeartbeatRequest> requests = interceptor.getHeartbeatRequests();
    Assume.assumeFalse(
        "No heartbeats captured — test server may not support worker heartbeat capability",
        requests.isEmpty());

    io.temporal.api.worker.v1.WorkerHeartbeat hb = requests.get(0).getWorkerHeartbeat(0);
    assertTrue("deployment_version should be set", hb.hasDeploymentVersion());
    assertEquals(
        "deployment_version.deployment_name should match configured value",
        TEST_DEPLOYMENT_NAME,
        hb.getDeploymentVersion().getDeploymentName());
    assertEquals(
        "deployment_version.build_id should match configured value",
        TEST_BUILD_ID,
        hb.getDeploymentVersion().getBuildId());

    testWorkflowRule.getTestEnvironment().shutdown();
    testWorkflowRule.getTestEnvironment().awaitTermination(10, TimeUnit.SECONDS);
  }
}
