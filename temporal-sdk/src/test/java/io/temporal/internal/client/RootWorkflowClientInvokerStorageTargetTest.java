package io.temporal.internal.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionResponse;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.interceptors.Header;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor.WorkflowSignalInput;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor.WorkflowStartInput;
import io.temporal.internal.client.external.GenericWorkflowClient;
import io.temporal.internal.payload.storage.ExternalStorageRunner;
import io.temporal.payload.storage.ExternalStorage;
import io.temporal.payload.storage.StorageDriver;
import io.temporal.payload.storage.StorageDriverClaim;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import io.temporal.payload.storage.StorageDriverStoreContext;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import io.temporal.payload.storage.StorageDriverWorkflowInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;

public class RootWorkflowClientInvokerStorageTargetTest {

  private static final String NAMESPACE = "test-namespace";

  @Test
  public void startCarriesTheWorkflowTypeButNoRunIdYet() {
    CapturingDriver driver = new CapturingDriver();
    GenericWorkflowClient rpc = mock(GenericWorkflowClient.class);
    when(rpc.start(any())).thenReturn(StartWorkflowExecutionResponse.getDefaultInstance());

    invoker(rpc, driver)
        .start(
            new WorkflowStartInput(
                "wf-1",
                "MyWorkflowType",
                Header.empty(),
                new Object[] {"argument"},
                WorkflowOptions.newBuilder().setTaskQueue("tq").build()));

    StorageDriverWorkflowInfo target = (StorageDriverWorkflowInfo) driver.lastTarget;
    assertEquals(NAMESPACE, target.getNamespace());
    assertEquals("wf-1", target.getId());
    assertEquals("MyWorkflowType", target.getType());
    assertNull(target.getRunId());
  }

  @Test
  public void signalCarriesTheRunId() {
    CapturingDriver driver = new CapturingDriver();
    GenericWorkflowClient rpc = mock(GenericWorkflowClient.class);

    invoker(rpc, driver)
        .signal(
            new WorkflowSignalInput(
                WorkflowExecution.newBuilder().setWorkflowId("wf-2").setRunId("run-9").build(),
                "mySignal",
                Header.empty(),
                new Object[] {"argument"}));

    StorageDriverWorkflowInfo target = (StorageDriverWorkflowInfo) driver.lastTarget;
    assertEquals("wf-2", target.getId());
    assertEquals("run-9", target.getRunId());
  }

  @Test
  public void anAbsentRunIdArrivesAsNullNotEmptyString() {
    CapturingDriver driver = new CapturingDriver();
    GenericWorkflowClient rpc = mock(GenericWorkflowClient.class);

    invoker(rpc, driver)
        .signal(
            new WorkflowSignalInput(
                WorkflowExecution.newBuilder().setWorkflowId("wf-3").build(),
                "mySignal",
                Header.empty(),
                new Object[] {"argument"}));

    assertNull(((StorageDriverWorkflowInfo) driver.lastTarget).getRunId());
  }

  private static RootWorkflowClientInvoker invoker(
      GenericWorkflowClient rpc, StorageDriver driver) {
    return new RootWorkflowClientInvoker(
        rpc,
        WorkflowClientOptions.newBuilder().setNamespace(NAMESPACE).validateAndBuildWithDefaults(),
        new WorkerFactoryRegistry(),
        ExternalStorageRunner.create(
            ExternalStorage.newBuilder().setDriver(driver).setPayloadSizeThreshold(0).build()));
  }

  private static final class CapturingDriver implements StorageDriver {
    volatile StorageDriverTargetInfo lastTarget;
    private int counter = 0;

    @Override
    public String getName() {
      return "test";
    }

    @Override
    public String getType() {
      return "test.capturing";
    }

    @Override
    public synchronized CompletableFuture<List<StorageDriverClaim>> store(
        StorageDriverStoreContext context, List<Payload> payloads) {
      lastTarget = context.getTarget();
      List<StorageDriverClaim> claims = new ArrayList<>();
      for (int i = 0; i < payloads.size(); i++) {
        claims.add(new StorageDriverClaim(Collections.singletonMap("key", "k-" + (counter++))));
      }
      return CompletableFuture.completedFuture(claims);
    }

    @Override
    public CompletableFuture<List<Payload>> retrieve(
        StorageDriverRetrieveContext context, List<StorageDriverClaim> claims) {
      throw new UnsupportedOperationException();
    }
  }
}
