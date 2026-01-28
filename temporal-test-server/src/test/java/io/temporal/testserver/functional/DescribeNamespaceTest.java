package io.temporal.testserver.functional;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeFalse;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.enums.v1.NamespaceState;
import io.temporal.api.workflowservice.v1.DescribeNamespaceRequest;
import io.temporal.api.workflowservice.v1.DescribeNamespaceResponse;
import io.temporal.internal.docker.RegisterTestNamespace;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import org.junit.Rule;
import org.junit.Test;

public class DescribeNamespaceTest {
  @Rule public SDKTestWorkflowRule testWorkflowRule = SDKTestWorkflowRule.newBuilder().build();

  @Test
  public void testDescribeNamespace() {
    DescribeNamespaceResponse describeNamespaceResponse =
        testWorkflowRule
            .getWorkflowServiceStubs()
            .blockingStub()
            .describeNamespace(
                DescribeNamespaceRequest.newBuilder()
                    .setNamespace(RegisterTestNamespace.NAMESPACE)
                    .build());
    assertEquals(
        NamespaceState.NAMESPACE_STATE_REGISTERED,
        describeNamespaceResponse.getNamespaceInfo().getState());
    assertEquals(
        RegisterTestNamespace.NAMESPACE, describeNamespaceResponse.getNamespaceInfo().getName());
    assertTrue(describeNamespaceResponse.getNamespaceInfo().getId().length() > 0);
  }

  @Test
  public void testDescribeNamespaceCapabilities() {
    assumeFalse(
        "Real Server doesn't support namespace capabilities yet",
        SDKTestWorkflowRule.useExternalService);

    DescribeNamespaceResponse describeNamespaceResponse =
        testWorkflowRule
            .getWorkflowServiceStubs()
            .blockingStub()
            .describeNamespace(
                DescribeNamespaceRequest.newBuilder()
                    .setNamespace(RegisterTestNamespace.NAMESPACE)
                    .build());

    assertTrue(
        describeNamespaceResponse.getNamespaceInfo().getCapabilities().getEagerWorkflowStart());
    assertTrue(describeNamespaceResponse.getNamespaceInfo().getCapabilities().getSyncUpdate());
    assertTrue(describeNamespaceResponse.getNamespaceInfo().getCapabilities().getAsyncUpdate());
  }

  @Test
  public void noNamespaceSet() {
    StatusRuntimeException ex =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                testWorkflowRule
                    .getWorkflowServiceStubs()
                    .blockingStub()
                    .describeNamespace(DescribeNamespaceRequest.newBuilder().build()));
    assertEquals(Status.Code.INVALID_ARGUMENT, ex.getStatus().getCode());
    assertEquals("Namespace not set on request.", ex.getStatus().getDescription());
  }
}
