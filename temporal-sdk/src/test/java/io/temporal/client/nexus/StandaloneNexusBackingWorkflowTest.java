package io.temporal.client.nexus;

import static org.junit.Assume.assumeTrue;

import io.nexusrpc.Operation;
import io.nexusrpc.Service;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.api.common.v1.Callback;
import io.temporal.api.common.v1.Link;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.api.nexus.v1.Endpoint;
import io.temporal.client.NexusOperationExecutionDescription;
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
 * Behavior tests for standalone Nexus operations whose handler is {@link WorkflowRunOperation},
 * i.e. each SANO is backed by a workflow. Shares one fixture (a workflow that awaits forever) so
 * individual tests can exercise cancel propagation, bidirectional link plumbing, and any other
 * behavior that depends on the SANO ↔ backing-workflow relationship.
 */
public class StandaloneNexusBackingWorkflowTest {

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

  /**
   * Verifies bidirectional linking between the standalone Nexus operation and the backing workflow
   * it starts.
   *
   * <ul>
   *   <li>Forward: a {@code Link.NexusOperation} pointing at the SANO record is attached to the
   *       backing workflow's WorkflowExecutionStarted completion callback (visible on {@code
   *       attrs.getCompletionCallbacks(i).getLinks(j)}).
   *   <li>Backward: a {@code Link.WorkflowEvent} pointing at the backing workflow's
   *       WorkflowExecutionStarted event is stored on the SANO record's {@code
   *       NexusOperationExecutionInfo.links} (visible via {@code handle.describe()}).
   * </ul>
   */
  @Test
  public void linkForwardedToBackingWorkflowCallback() throws Exception {
    Endpoint endpoint = testWorkflowRule.getNexusEndpoint();
    UntypedNexusServiceClient svc =
        testWorkflowRule
            .getNexusClient()
            .newUntypedNexusServiceClient(
                endpoint.getSpec().getName(), CancelTargetNexusService.class.getSimpleName());

    String operationId = UUID.randomUUID().toString();
    UntypedNexusOperationHandle handle =
        svc.start(
            "operation",
            StartNexusOperationOptions.newBuilder()
                .setId(operationId)
                .setScheduleToCloseTimeout(Duration.ofSeconds(30))
                .build(),
            "ignored");
    String operationRunId = handle.getNexusOperationRunId();
    Assert.assertNotNull("expected SANO run id to be populated by start", operationRunId);

    String workflowId = waitForWorkflowIdCaptured(Duration.ofSeconds(8));

    try {
      History history = testWorkflowRule.getWorkflowClient().fetchHistory(workflowId).getHistory();
      HistoryEvent startedEvent = history.getEventsList().get(0);
      WorkflowExecutionStartedEventAttributes attrs =
          startedEvent.getWorkflowExecutionStartedEventAttributes();

      Link.NexusOperation found = null;
      for (Callback cb : attrs.getCompletionCallbacksList()) {
        for (Link link : cb.getLinksList()) {
          if (link.hasNexusOperation()) {
            found = link.getNexusOperation();
            break;
          }
        }
        if (found != null) {
          break;
        }
      }
      Assert.assertNotNull(
          "expected Link.NexusOperation on a completion callback of the backing workflow", found);
      Assert.assertEquals(
          testWorkflowRule.getWorkflowClient().getOptions().getNamespace(), found.getNamespace());
      Assert.assertEquals(operationId, found.getOperationId());
      Assert.assertEquals(operationRunId, found.getRunId());

      // Backward direction: SANO record's info.links should carry a Link.WorkflowEvent referencing
      // the backing workflow's WorkflowExecutionStarted event.
      NexusOperationExecutionDescription desc = handle.describe();
      Link.WorkflowEvent backLink = null;
      for (Link link : desc.getRawInfo().getLinksList()) {
        if (link.hasWorkflowEvent() && workflowId.equals(link.getWorkflowEvent().getWorkflowId())) {
          backLink = link.getWorkflowEvent();
          break;
        }
      }
      Assert.assertNotNull(
          "expected Link.WorkflowEvent on the SANO record's info.links pointing at the backing"
              + " workflow",
          backLink);
      Assert.assertEquals(
          testWorkflowRule.getWorkflowClient().getOptions().getNamespace(),
          backLink.getNamespace());
      EventType backLinkEventType =
          backLink.hasRequestIdRef()
              ? backLink.getRequestIdRef().getEventType()
              : backLink.getEventRef().getEventType();
      Assert.assertEquals(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED, backLinkEventType);
    } finally {
      // Workflow awaits forever; cancel so the test rule shuts down cleanly.
      handle.cancel("link-test-cleanup");
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
