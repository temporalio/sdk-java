package io.temporal.workflow.nexus;

import static org.junit.Assume.assumeTrue;

import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.BatchRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.nexus.Nexus;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.NexusOperationOptions;
import io.temporal.workflow.NexusServiceOptions;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

/**
 * Verifies link propagation in both directions when a Nexus operation handler interacts with a
 * workflow via signal. Exercises two paths in one run:
 *
 * <ol>
 *   <li>The handler issues {@code SignalWithStartWorkflowExecution} (atomically creates the callee
 *       and delivers its first signal). Backlink flows on {@code
 *       SignalWithStartWorkflowExecutionResponse.signal_link}.
 *   <li>The handler then issues a plain {@code SignalWorkflowExecution} against the now-running
 *       callee. Backlink flows on {@code SignalWorkflowExecutionResponse.link}.
 * </ol>
 *
 * <p>Both back-direction fields require Temporal server &ge; 1.31 with {@code
 * EnableCHASMSignalBacklinks=true}. The in-memory test server does not implement these paths, so
 * this test is skipped unless a real server is in use.
 */
public class SignalOperationLinkingTest extends BaseNexusTest {

  private static final String MODE_SIGNAL_WITH_START = "signalWithStart";
  private static final String MODE_SIGNAL = "signal";

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(SignalCallerWorkflow.class, SignalCalleeWorkflowImpl.class)
          .setNexusServiceImplementation(new SignalingNexusServiceImpl())
          .build();

  @BeforeClass
  public static void requireExternalService() {
    // The server-side backlink implementation (temporalio/temporal#9897) is gated by the
    // EnableCHASMSignalBacklinks dynamic config flag and is only present in real servers. The
    // in-memory test server does not implement it.
    assumeTrue(
        "signal backlinks require a real server with EnableCHASMSignalBacklinks=true",
        SDKTestWorkflowRule.useExternalService);
  }

  @Override
  protected SDKTestWorkflowRule getTestWorkflowRule() {
    return testWorkflowRule;
  }

  @Test
  public void testSignalOperationLinks() {
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    String calleeWorkflowId = "signal-callee-" + UUID.randomUUID();

    // Drive the caller; this invokes the Nexus operation TWICE:
    //   1. mode=signalWithStart: handler creates the callee + delivers signal "first".
    //   2. mode=signal:          handler sends signal "second" to the now-running callee.
    TestWorkflows.TestWorkflow1 callerStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class, "caller");
    String result = callerStub.execute(calleeWorkflowId);
    Assert.assertEquals("ok:signalWithStart|ok:signal", result);

    // Callee awaits both signals then returns the payloads it saw.
    String calleeResult = client.newUntypedWorkflowStub(calleeWorkflowId).getResult(String.class);
    Assert.assertEquals("first,second", calleeResult);

    String callerWorkflowId = WorkflowStub.fromTyped(callerStub).getExecution().getWorkflowId();
    History callerHistory = client.fetchHistory(callerWorkflowId).getHistory();
    History calleeHistory = client.fetchHistory(calleeWorkflowId).getHistory();

    // Caller → callee: each WorkflowExecutionSignaled event on the callee has a link pointing at
    // the caller's NexusOperationScheduled event for the corresponding phase.
    List<HistoryEvent> signaledEvents =
        getAllEventsOfType(calleeHistory, EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED);
    Assert.assertEquals(
        "expected two WorkflowExecutionSignaled events on the callee", 2, signaledEvents.size());
    for (HistoryEvent signaled : signaledEvents) {
      Assert.assertTrue(
          "expected at least one link on each WorkflowExecutionSignaled event",
          signaled.getLinksCount() >= 1);
      Assert.assertEquals(
          "signaled-event link should reference the caller workflow",
          callerWorkflowId,
          signaled.getLinks(0).getWorkflowEvent().getWorkflowId());
      Assert.assertEquals(
          EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED,
          signaled.getLinks(0).getWorkflowEvent().getEventRef().getEventType());
    }

    // Callee → caller: each NexusOperationCompleted event on the caller has a backlink to the
    // corresponding signaled event on the callee. Server PR #9897 keys these via
    // RequestIdReference rather than EventReference, so we accept either oneof variant.
    List<HistoryEvent> nexusCompletedEvents =
        getAllEventsOfType(callerHistory, EventType.EVENT_TYPE_NEXUS_OPERATION_COMPLETED);
    Assert.assertEquals(
        "expected two NexusOperationCompleted events on the caller",
        2,
        nexusCompletedEvents.size());
    for (HistoryEvent nexusCompleted : nexusCompletedEvents) {
      Assert.assertTrue(
          "expected a signal-event backlink on NexusOperationCompleted",
          nexusCompleted.getLinksCount() >= 1);
      io.temporal.api.common.v1.Link.WorkflowEvent backlink =
          nexusCompleted.getLinks(0).getWorkflowEvent();
      Assert.assertEquals(calleeWorkflowId, backlink.getWorkflowId());
      EventType backlinkEventType =
          backlink.hasRequestIdRef()
              ? backlink.getRequestIdRef().getEventType()
              : backlink.getEventRef().getEventType();
      Assert.assertEquals(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED, backlinkEventType);
    }
  }

  /** Find all history events of a given type, in order. */
  private static List<HistoryEvent> getAllEventsOfType(History history, EventType type) {
    List<HistoryEvent> out = new ArrayList<>();
    for (HistoryEvent e : history.getEventsList()) {
      if (e.getEventType() == type) {
        out.add(e);
      }
    }
    return out;
  }

  /**
   * Caller workflow: invokes the Nexus operation twice. First call creates the callee via
   * signalWithStart, second call sends a regular signal to it.
   */
  public static class SignalCallerWorkflow implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String calleeWorkflowId) {
      NexusServiceOptions serviceOptions =
          NexusServiceOptions.newBuilder()
              .setEndpoint(getEndpointName())
              .setOperationOptions(
                  NexusOperationOptions.newBuilder()
                      .setScheduleToCloseTimeout(Duration.ofSeconds(30))
                      .build())
              .build();
      TestNexusServices.TestNexusService1 stub =
          Workflow.newNexusServiceStub(TestNexusServices.TestNexusService1.class, serviceOptions);
      String r1 = stub.operation(MODE_SIGNAL_WITH_START + ":" + calleeWorkflowId);
      String r2 = stub.operation(MODE_SIGNAL + ":" + calleeWorkflowId);
      return r1 + "|" + r2;
    }
  }

  /** Callee workflow: awaits two signals and returns the joined payloads. */
  @WorkflowInterface
  public interface SignalCalleeWorkflow {
    @WorkflowMethod
    String execute();

    @SignalMethod
    void ping(String msg);
  }

  public static class SignalCalleeWorkflowImpl implements SignalCalleeWorkflow {
    private final List<String> received = new ArrayList<>();

    @Override
    public String execute() {
      Workflow.await(() -> received.size() >= 2);
      return String.join(",", received);
    }

    @Override
    public void ping(String msg) {
      received.add(msg);
    }
  }

  /**
   * Nexus service whose single operation either creates the callee via {@code signalWithStart} or
   * delivers a regular signal, based on a mode prefix encoded in the input.
   */
  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public static class SignalingNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return OperationHandler.sync(
          (ctx, details, input) -> {
            String[] parts = input.split(":", 2);
            String mode = parts[0];
            String calleeWorkflowId = parts[1];
            io.temporal.nexus.NexusOperationContext opCtx = Nexus.getOperationContext();
            WorkflowClient client = opCtx.getWorkflowClient();
            if (MODE_SIGNAL_WITH_START.equals(mode)) {
              // signalWithStart needs a "to-be-started" stub built from WorkflowOptions, with
              // taskQueue set (the server validates it even when the workflow already exists).
              SignalCalleeWorkflow startStub =
                  client.newWorkflowStub(
                      SignalCalleeWorkflow.class,
                      WorkflowOptions.newBuilder()
                          .setWorkflowId(calleeWorkflowId)
                          .setTaskQueue(opCtx.getInfo().getTaskQueue())
                          .build());
              BatchRequest batch = client.newSignalWithStartRequest();
              batch.add(startStub::execute);
              batch.add(startStub::ping, "first");
              client.signalWithStart(batch);
            } else {
              // A plain signal targets an existing execution; use the workflowId-only overload.
              SignalCalleeWorkflow existingStub =
                  client.newWorkflowStub(SignalCalleeWorkflow.class, calleeWorkflowId);
              existingStub.ping("second");
            }
            return "ok:" + mode;
          });
    }
  }
}
