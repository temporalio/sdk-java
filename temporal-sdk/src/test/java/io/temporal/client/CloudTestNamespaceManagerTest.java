package io.temporal.client;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.util.Durations;
import io.temporal.api.cloud.cloudservice.v1.CreateNamespaceRequest;
import io.temporal.api.cloud.cloudservice.v1.DeleteNamespaceRequest;
import io.temporal.api.cloud.operation.v1.AsyncOperation;
import org.junit.Test;

public class CloudTestNamespaceManagerTest {

  @Test
  public void createNamespaceRequestUsesIsolatedMtlsSpec() {
    byte[] clientCa = new byte[] {1, 2, 3};

    CreateNamespaceRequest request =
        CloudTestNamespaceManager.createNamespaceRequest("sdk-java-ci-123-2", clientCa);

    assertEquals("sdk-java-ci-123-2", request.getSpec().getName());
    assertEquals(1, request.getSpec().getRetentionDays());
    assertEquals(1, request.getSpec().getReplicasCount());
    assertEquals(
        CloudTestNamespaceManager.CLOUD_REGION, request.getSpec().getReplicas(0).getRegion());
    assertTrue(request.getSpec().getMtlsAuth().getEnabled());
    assertArrayEquals(
        clientCa, request.getSpec().getMtlsAuth().getAcceptedClientCa().toByteArray());
  }

  @Test
  public void deleteNamespaceRequestUsesResourceVersion() {
    DeleteNamespaceRequest request =
        CloudTestNamespaceManager.deleteNamespaceRequest(
            "sdk-java-ci-123-2.account", "resource-version");

    assertEquals("sdk-java-ci-123-2.account", request.getNamespace());
    assertEquals("resource-version", request.getResourceVersion());
  }

  @Test
  public void operationStatesDistinguishPendingFulfilledAndRejected() {
    assertFalse(
        CloudTestNamespaceManager.operationComplete(operation(AsyncOperation.State.STATE_PENDING)));
    assertTrue(
        CloudTestNamespaceManager.operationComplete(
            operation(AsyncOperation.State.STATE_FULFILLED)));

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                CloudTestNamespaceManager.operationComplete(
                    operation(AsyncOperation.State.STATE_REJECTED).toBuilder()
                        .setFailureReason("not allowed")
                        .build()));
    assertTrue(failure.getMessage().contains("STATE_REJECTED"));
    assertTrue(failure.getMessage().contains("not allowed"));
  }

  @Test
  public void pollingUsesDefaultAndMinimumDelays() {
    assertEquals(
        CloudTestNamespaceManager.DEFAULT_POLL_DELAY.toMillis(),
        CloudTestNamespaceManager.pollDelayMillis(operation(AsyncOperation.State.STATE_PENDING)));
    assertEquals(
        CloudTestNamespaceManager.MIN_POLL_DELAY.toMillis(),
        CloudTestNamespaceManager.pollDelayMillis(
            operation(AsyncOperation.State.STATE_PENDING).toBuilder()
                .setCheckDuration(Durations.fromMillis(100))
                .build()));
  }

  @Test
  public void pollingRequiresOperationId() {
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                CloudTestNamespaceManager.waitForOperation(
                    null, AsyncOperation.getDefaultInstance()));

    assertEquals("Cloud operation response did not include an ID.", failure.getMessage());
  }

  private static AsyncOperation operation(AsyncOperation.State state) {
    return AsyncOperation.newBuilder().setId("operation-id").setState(state).build();
  }
}
