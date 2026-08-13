package io.temporal.client.nexus;

import static org.junit.Assume.assumeTrue;

import io.nexusrpc.Operation;
import io.nexusrpc.Service;
import io.nexusrpc.handler.OperationContext;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.api.common.v1.Link;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.nexus.v1.Endpoint;
import io.temporal.client.BatchRequest;
import io.temporal.client.NexusOperationExecutionDescription;
import io.temporal.client.StartNexusOperationOptions;
import io.temporal.client.UntypedNexusOperationHandle;
import io.temporal.client.UntypedNexusServiceClient;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.nexus.Nexus;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Verifies bidirectional link propagation when a Nexus operation handler signal-with-starts a
 * workflow and the operation itself was kicked off through a standalone Nexus client (SANO).
 *
 * <ul>
 *   <li>Forward: the inbound Nexus task carries a {@code Link.NexusOperation} pointing at the SANO
 *       record. The handler forwards it onto the signal-with-start request so the callee's {@code
 *       WorkflowExecutionSignaled} (and {@code WorkflowExecutionStarted}) events carry that
 *       backlink.
 *   <li>Backward: the server returns a {@code signal_link} on {@code
 *       SignalWithStartWorkflowExecutionResponse} pointing at the callee's signal event. The
 *       handler drains it onto the {@code StartOperationResponse.links}, and the server stores it
 *       on the SANO record's {@code NexusOperationExecutionInfo.links}.
 * </ul>
 *
 * <p>Requires a real server: standalone Nexus operations and {@code EnableCHASMSignalBacklinks} are
 * not implemented by the in-memory test server. Test skips locally and runs in CI.
 */
public class StandaloneNexusSignalLinkingTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(SanoSignalCalleeWorkflowImpl.class)
          .setNexusServiceImplementation(new SanoSignalingNexusServiceImpl())
          .build();

  @Before
  public void requireStandaloneNexusSupport() {
    assumeTrue(
        "server does not support standalone Nexus operations and signal backlinks",
        testWorkflowRule.isUseExternalService());
  }

  @Test
  public void linksFlowBothDirectionsForSanoTriggeredSignal() throws Exception {
    Endpoint endpoint = testWorkflowRule.getNexusEndpoint();
    UntypedNexusServiceClient svc =
        testWorkflowRule
            .getNexusClient()
            .newUntypedNexusServiceClient(
                endpoint.getSpec().getName(), SanoSignalingNexusService.class.getSimpleName());

    String operationId = UUID.randomUUID().toString();
    String calleeWorkflowId = "sano-signal-callee-" + UUID.randomUUID();
    UntypedNexusOperationHandle handle =
        svc.start(
            "operation",
            StartNexusOperationOptions.newBuilder()
                .setId(operationId)
                .setScheduleToCloseTimeout(Duration.ofSeconds(30))
                .build(),
            calleeWorkflowId);
    String operationRunId = handle.getNexusOperationRunId();
    Assert.assertNotNull("expected SANO run id to be populated by start", operationRunId);

    String result = handle.getResult(30, TimeUnit.SECONDS, String.class);
    Assert.assertEquals("signaled", result);

    // The callee was signal-with-started by the handler and exits once it sees one signal.
    String calleeResult =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub(calleeWorkflowId)
            .getResult(String.class);
    Assert.assertEquals("from-sano", calleeResult);

    // Forward direction: callee's WorkflowExecutionSignaled event carries a Link.NexusOperation
    // pointing at the SANO record. The same backlink also lands on WorkflowExecutionStarted via
    // the SWS request's links field; assert on the signal event because that's the one the SANO
    // backlink is principally for.
    History calleeHistory =
        testWorkflowRule.getWorkflowClient().fetchHistory(calleeWorkflowId).getHistory();
    HistoryEvent signaledEvent =
        findEventOfType(calleeHistory, EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED);
    Assert.assertNotNull("expected a WorkflowExecutionSignaled event on the callee", signaledEvent);
    Link.NexusOperation forwardLink = null;
    for (Link link : signaledEvent.getLinksList()) {
      if (link.hasNexusOperation()) {
        forwardLink = link.getNexusOperation();
        break;
      }
    }
    Assert.assertNotNull(
        "expected Link.NexusOperation on callee's WorkflowExecutionSignaled event", forwardLink);
    Assert.assertEquals(
        testWorkflowRule.getWorkflowClient().getOptions().getNamespace(),
        forwardLink.getNamespace());
    Assert.assertEquals(operationId, forwardLink.getOperationId());
    Assert.assertEquals(operationRunId, forwardLink.getRunId());

    // Backward direction: the signal_link returned on the SWS response is drained onto the
    // StartOperationResponse.links by the handler, and the server stores it on the SANO record.
    // Read via describe → NexusOperationExecutionInfo.links.
    NexusOperationExecutionDescription desc = handle.describe();
    Link backwardLink = null;
    for (Link link : desc.getRawInfo().getLinksList()) {
      if (link.hasWorkflowEvent()
          && calleeWorkflowId.equals(link.getWorkflowEvent().getWorkflowId())) {
        backwardLink = link;
        break;
      }
    }
    Assert.assertNotNull(
        "expected the signal response link on the SANO record's info.links", backwardLink);
    EventType backwardLinkEventType =
        backwardLink.getWorkflowEvent().hasRequestIdRef()
            ? backwardLink.getWorkflowEvent().getRequestIdRef().getEventType()
            : backwardLink.getWorkflowEvent().getEventRef().getEventType();
    Assert.assertEquals(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED, backwardLinkEventType);
  }

  private static HistoryEvent findEventOfType(History history, EventType type) {
    for (HistoryEvent e : history.getEventsList()) {
      if (e.getEventType() == type) {
        return e;
      }
    }
    return null;
  }

  @Service
  public interface SanoSignalingNexusService {
    @Operation
    String operation(String calleeWorkflowId);
  }

  @ServiceImpl(service = SanoSignalingNexusService.class)
  public static class SanoSignalingNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return OperationHandler.sync(
          (OperationContext ctx,
              io.nexusrpc.handler.OperationStartDetails details,
              String input) -> {
            WorkflowClient client = Nexus.getOperationContext().getWorkflowClient();
            String tq = Nexus.getOperationContext().getInfo().getTaskQueue();
            SanoSignalCalleeWorkflow startStub =
                client.newWorkflowStub(
                    SanoSignalCalleeWorkflow.class,
                    WorkflowOptions.newBuilder().setWorkflowId(input).setTaskQueue(tq).build());
            BatchRequest batch = client.newSignalWithStartRequest();
            batch.add(startStub::execute);
            batch.add(startStub::ping, "from-sano");
            client.signalWithStart(batch);
            return "signaled";
          });
    }
  }

  @WorkflowInterface
  public interface SanoSignalCalleeWorkflow {
    @WorkflowMethod
    String execute();

    @SignalMethod
    void ping(String msg);
  }

  public static class SanoSignalCalleeWorkflowImpl implements SanoSignalCalleeWorkflow {
    private String received;

    @Override
    public String execute() {
      Workflow.await(() -> received != null);
      return received;
    }

    @Override
    public void ping(String msg) {
      received = msg;
    }
  }
}
