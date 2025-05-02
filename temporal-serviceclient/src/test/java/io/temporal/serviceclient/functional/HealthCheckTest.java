package io.temporal.serviceclient.functional;

import static org.junit.Assert.*;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckResponse;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import org.junit.Rule;
import org.junit.Test;

public class HealthCheckTest {

  @Rule public SDKTestWorkflowRule testWorkflowRule = SDKTestWorkflowRule.newBuilder().build();

  @Test
  public void testNormalHealthCheck() {
    HealthCheckResponse healthCheckResponse =
        testWorkflowRule.getWorkflowServiceStubs().healthCheck();
    assertEquals(HealthCheckResponse.ServingStatus.SERVING, healthCheckResponse.getStatus());
  }

  @Test
  public void testUnavailableServer() {
    WorkflowServiceStubsOptions options = testWorkflowRule.getWorkflowServiceStubs().getOptions();
    options =
        WorkflowServiceStubsOptions.newBuilder(options)
            .setTarget("localhost:1234")
            .setChannel(null)
            .validateAndBuildWithDefaults();
    WorkflowServiceStubs lazyStubs = WorkflowServiceStubs.newServiceStubs(options);

    StatusRuntimeException ex = assertThrows(StatusRuntimeException.class, lazyStubs::healthCheck);
    assertEquals(Status.Code.UNAVAILABLE, ex.getStatus().getCode());
  }
}
