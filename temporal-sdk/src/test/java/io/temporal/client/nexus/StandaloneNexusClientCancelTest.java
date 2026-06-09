package io.temporal.client.nexus;

import static org.junit.Assume.assumeTrue;

import io.nexusrpc.Operation;
import io.nexusrpc.Service;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.api.nexus.v1.Endpoint;
import io.temporal.client.StartNexusOperationOptions;
import io.temporal.client.UntypedNexusOperationHandle;
import io.temporal.client.UntypedNexusServiceClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.CanceledFailure;
import io.temporal.nexus.Nexus;
import io.temporal.nexus.WorkflowRunOperation;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Verifies that {@link UntypedNexusOperationHandle#cancel()} from a standalone client propagates
 * through the server to the handler workflow backing a Nexus operation. Mirrors {@code
 * sdk-go/test/nexus_test.go TestNexusWorkflowRunOperation}: start a Nexus operation backed by a
 * workflow that awaits forever, cancel via the standalone client handle, then assert the backing
 * workflow ends with {@link CanceledFailure}.
 */
public class StandaloneNexusClientCancelTest {

  static final AtomicReference<String> capturedWorkflowId = new AtomicReference<>();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(CancelTargetWorkflowImpl.class)
          .setNexusServiceImplementation(new CancelTargetNexusServiceImpl())
          .build();

  @Before
  public void requireStandaloneNexusSupportAndReset() {
    assumeTrue(
        "server does not support standalone Nexus operations",
        testWorkflowRule.isUseExternalService());
    capturedWorkflowId.set(null);
  }

  @Test
  public void cancelPropagatesToBackingWorkflow() throws Exception {
    Endpoint endpoint = testWorkflowRule.getNexusEndpoint();
    UntypedNexusServiceClient svc =
        testWorkflowRule
            .getNexusClient()
            .newUntypedNexusServiceClient(
                endpoint.getSpec().getName(), CancelTargetNexusService.class.getSimpleName());

    UntypedNexusOperationHandle handle =
        svc.start(
            "operation",
            StartNexusOperationOptions.newBuilder()
                .setId(UUID.randomUUID().toString())
                .setScheduleToCloseTimeout(Duration.ofSeconds(30))
                .build(),
            "ignored");

    String workflowId = waitForWorkflowIdCaptured(Duration.ofSeconds(8));

    handle.cancel("standalone-client-cancel-test");

    WorkflowStub stub = testWorkflowRule.getWorkflowClient().newUntypedWorkflowStub(workflowId);
    try {
      stub.getResult(Void.class);
      Assert.fail("expected backing workflow to terminate with cancellation");
    } catch (WorkflowFailedException expected) {
      Throwable cause = expected.getCause();
      Assert.assertTrue(
          "expected cause to be CanceledFailure, got "
              + (cause == null ? "null" : cause.getClass().getSimpleName()),
          cause instanceof CanceledFailure);
    }
  }

  private static String waitForWorkflowIdCaptured(Duration budget) throws InterruptedException {
    long deadlineNanos = System.nanoTime() + budget.toNanos();
    while (capturedWorkflowId.get() == null && System.nanoTime() < deadlineNanos) {
      Thread.sleep(100);
    }
    String id = capturedWorkflowId.get();
    Assert.assertNotNull(
        "handler workflow did not start (workflowId never captured) within " + budget, id);
    return id;
  }

  @WorkflowInterface
  public interface CancelTargetWorkflow {
    @WorkflowMethod
    Void execute(String ignored);
  }

  public static class CancelTargetWorkflowImpl implements CancelTargetWorkflow {
    @Override
    public Void execute(String ignored) {
      capturedWorkflowId.set(Workflow.getInfo().getWorkflowId());
      Workflow.await(() -> false);
      return null;
    }
  }

  @Service
  public interface CancelTargetNexusService {
    @Operation
    Void operation(String ignored);
  }

  @ServiceImpl(service = CancelTargetNexusService.class)
  public static class CancelTargetNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, Void> operation() {
      return WorkflowRunOperation.fromWorkflowMethod(
          (context, details, input) ->
              Nexus.getOperationContext()
                      .getWorkflowClient()
                      .newWorkflowStub(
                          CancelTargetWorkflow.class,
                          WorkflowOptions.newBuilder()
                              .setWorkflowId("cancel-target-" + details.getRequestId())
                              .build())
                  ::execute);
    }
  }
}
