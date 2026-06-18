package io.temporal.workflow.nexus;

import static org.junit.Assume.assumeTrue;

import io.nexusrpc.handler.OperationCancelDetails;
import io.nexusrpc.handler.OperationContext;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.OperationStartDetails;
import io.nexusrpc.handler.OperationStartResult;
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
import io.temporal.workflow.NexusOperationHandle;
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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

/**
 * Verifies link propagation in both directions when a Nexus operation handler interacts with a
 * workflow via signal. Covers three scenarios:
 *
 * <ul>
 *   <li>{@link #testSignalOperationLinks()} — sync handler, two signals (signalWithStart + plain
 *       signal).
 *   <li>{@link #testMultiSignalOperationLinks()} — one Nexus operation signals three different
 *       callees; verifies all three response links land on the caller's single {@code
 *       NexusOperationCompleted} event.
 *   <li>{@link #testAsyncSignalOperationLinks()} — handler returns an async result after signaling;
 *       verifies the response link lands on {@code NexusOperationStarted} (the async response path
 *       in {@link io.temporal.internal.nexus.NexusTaskHandlerImpl}).
 * </ul>
 *
 * <p>All tests require Temporal server &ge; 1.31 with {@code EnableCHASMSignalBacklinks=true}; the
 * in-memory test server does not implement this path so the class is skipped unless a real server
 * is in use.
 */
public class SignalOperationLinkingTest {

  private static final String MODE_SIGNAL_WITH_START = "signalWithStart";
  private static final String MODE_SIGNAL = "signal";
  private static final String MODE_MULTI_SIGNAL_WITH_START = "multi";
  private static final String MODE_ASYNC_SIGNAL_WITH_START = "asyncSignalWithStart";

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(SignalCallerWorkflow.class, SignalCalleeWorkflowImpl.class)
          .setNexusServiceImplementation(new SignalingNexusServiceImpl())
          .build();

  @BeforeClass
  public static void requireExternalService() {
    // The server-side response link implementation (temporalio/temporal#9897) is gated by
    // EnableCHASMSignalBacklinks and is only present in real servers.
    assumeTrue(
        "signal response links require a real server with EnableCHASMSignalBacklinks=true",
        SDKTestWorkflowRule.useExternalService);
  }

  // ── Tests ────────────────────────────────────────────────────────────────────────────────

  @Test
  public void testSignalOperationLinks() {
    runTwoSignalScenario();
  }

  /**
   * One Nexus operation signals three different callees. The handler's three signal-class RPCs each
   * contribute a response link and all three end up on the caller's single {@code
   * NexusOperationCompleted} event.
   */
  @Test
  public void testMultiSignalOperationLinks() {
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    List<String> calleeIds = Arrays.asList("multicallee-a", "multicallee-b", "multicallee-c");

    TestWorkflows.TestWorkflow1 callerStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(
            TestWorkflows.TestWorkflow1.class, "multicaller");
    String result =
        callerStub.execute(MODE_MULTI_SIGNAL_WITH_START + ":" + String.join(",", calleeIds));
    Assert.assertEquals("ok:multi:" + String.join(",", calleeIds), result);

    // Each callee gets one signal and completes.
    for (String calleeId : calleeIds) {
      String calleeResult = client.newUntypedWorkflowStub(calleeId).getResult(String.class);
      Assert.assertEquals("multi-signal", calleeResult);
    }

    String callerWorkflowId = WorkflowStub.fromTyped(callerStub).getExecution().getWorkflowId();
    History callerHistory = client.fetchHistory(callerWorkflowId).getHistory();

    // Caller → each callee: forward links on every callee's WorkflowExecutionSignaled event.
    for (String calleeId : calleeIds) {
      History calleeHistory = client.fetchHistory(calleeId).getHistory();
      assertForwardLinks(calleeHistory, callerWorkflowId, /* expectedCount= */ 1);
    }

    // Callee → caller: the single NexusOperationCompleted carries one response link per callee.
    List<HistoryEvent> completedEvents =
        getAllEventsOfType(callerHistory, EventType.EVENT_TYPE_NEXUS_OPERATION_COMPLETED);
    Assert.assertEquals(
        "expected exactly one NexusOperationCompleted event", 1, completedEvents.size());
    HistoryEvent completed = completedEvents.get(0);
    Assert.assertEquals(
        "expected one response link per signaled callee",
        calleeIds.size(),
        completed.getLinksCount());
    List<String> responseLinkWorkflowIds = new ArrayList<>();
    for (int i = 0; i < completed.getLinksCount(); i++) {
      io.temporal.api.common.v1.Link.WorkflowEvent responseLink =
          completed.getLinks(i).getWorkflowEvent();
      responseLinkWorkflowIds.add(responseLink.getWorkflowId());
      EventType responseLinkEventType =
          responseLink.hasRequestIdRef()
              ? responseLink.getRequestIdRef().getEventType()
              : responseLink.getEventRef().getEventType();
      Assert.assertEquals(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED, responseLinkEventType);
    }
    Assert.assertTrue(
        "expected response links to reference all three callees: " + responseLinkWorkflowIds,
        responseLinkWorkflowIds.containsAll(calleeIds));
  }

  /**
   * Async response path: handler signals the callee then returns an async result. Verifies that the
   * response link lands on {@code NexusOperationStarted} (the async branch in
   * NexusTaskHandlerImpl).
   */
  @Test
  public void testAsyncSignalOperationLinks() {
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    String calleeWorkflowId = "async-callee";

    TestWorkflows.TestWorkflow1 callerStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(
            TestWorkflows.TestWorkflow1.class, "async-caller");
    String result = callerStub.execute(MODE_ASYNC_SIGNAL_WITH_START + ":" + calleeWorkflowId);
    Assert.assertEquals("async-started", result);

    String calleeResult = client.newUntypedWorkflowStub(calleeWorkflowId).getResult(String.class);
    Assert.assertEquals("async-signal", calleeResult);

    String callerWorkflowId = WorkflowStub.fromTyped(callerStub).getExecution().getWorkflowId();
    History callerHistory = client.fetchHistory(callerWorkflowId).getHistory();
    History calleeHistory = client.fetchHistory(calleeWorkflowId).getHistory();

    assertForwardLinks(calleeHistory, callerWorkflowId, /* expectedCount= */ 1);

    // Backward direction lands on NexusOperationStarted for the async response path.
    List<HistoryEvent> startedEvents =
        getAllEventsOfType(callerHistory, EventType.EVENT_TYPE_NEXUS_OPERATION_STARTED);
    Assert.assertEquals(
        "expected exactly one NexusOperationStarted event for the async op",
        1,
        startedEvents.size());
    assertResponseLink(startedEvents.get(0), calleeWorkflowId);
  }

  // ── Shared scenario + assertion helpers ──────────────────────────────────────────────────

  /** Drive the two-signal flow (signalWithStart + plain signal) and assert link propagation. */
  private void runTwoSignalScenario() {
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    String calleeWorkflowId = "callee";

    TestWorkflows.TestWorkflow1 callerStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class, "caller");
    String result = callerStub.execute("twoSync:" + calleeWorkflowId);
    Assert.assertEquals("ok:signalWithStart|ok:signal", result);

    String calleeResult = client.newUntypedWorkflowStub(calleeWorkflowId).getResult(String.class);
    Assert.assertEquals("first,second", calleeResult);

    String callerWorkflowId = WorkflowStub.fromTyped(callerStub).getExecution().getWorkflowId();
    History callerHistory = client.fetchHistory(callerWorkflowId).getHistory();
    History calleeHistory = client.fetchHistory(calleeWorkflowId).getHistory();

    assertForwardLinks(calleeHistory, callerWorkflowId, /* expectedCount= */ 2);

    List<HistoryEvent> completedEvents =
        getAllEventsOfType(callerHistory, EventType.EVENT_TYPE_NEXUS_OPERATION_COMPLETED);
    Assert.assertEquals(
        "expected two NexusOperationCompleted events on the caller", 2, completedEvents.size());
    for (HistoryEvent completed : completedEvents) {
      assertResponseLink(completed, calleeWorkflowId);
    }
  }

  /**
   * Assert that the callee history has {@code expectedCount} {@code WorkflowExecutionSignaled}
   * events, each linked back to the caller's {@code NexusOperationScheduled} event.
   */
  private static void assertForwardLinks(
      History calleeHistory, String callerWorkflowId, int expectedCount) {
    List<HistoryEvent> signaledEvents =
        getAllEventsOfType(calleeHistory, EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED);
    Assert.assertEquals(
        "expected " + expectedCount + " WorkflowExecutionSignaled events on the callee",
        expectedCount,
        signaledEvents.size());
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
  }

  /**
   * Assert that a single caller-side event ({@code NexusOperationCompleted} or {@code
   * NexusOperationStarted}) carries a response link to the callee's {@code
   * WorkflowExecutionSignaled} event. Server PR #9897 keys these via {@code RequestIdReference}
   * rather than {@code EventReference}, so we accept either oneof variant.
   */
  private static void assertResponseLink(HistoryEvent event, String calleeWorkflowId) {
    Assert.assertTrue(
        "expected a signal-event response link on " + event.getEventType().name(),
        event.getLinksCount() >= 1);
    io.temporal.api.common.v1.Link.WorkflowEvent responseLink =
        event.getLinks(0).getWorkflowEvent();
    Assert.assertEquals(calleeWorkflowId, responseLink.getWorkflowId());
    EventType responseLinkEventType =
        responseLink.hasRequestIdRef()
            ? responseLink.getRequestIdRef().getEventType()
            : responseLink.getEventRef().getEventType();
    Assert.assertEquals(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED, responseLinkEventType);
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

  // ── Workflows ────────────────────────────────────────────────────────────────────────────

  /**
   * Caller workflow. Branches on a mode prefix in the input:
   *
   * <ul>
   *   <li>{@code twoSync:<calleeId>} — invoke the nexus op twice synchronously (signalWithStart,
   *       then signal).
   *   <li>{@code multi:<n1>,<n2>,<n3>} — invoke the nexus op once synchronously; handler
   *       signalWithStart's each id.
   *   <li>{@code asyncSignalWithStart:<calleeId>} — invoke the nexus op asynchronously via {@code
   *       Workflow.startNexusOperation}; wait for execution start and return without waiting for
   *       the operation result.
   * </ul>
   */
  public static class SignalCallerWorkflow implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String input) {
      String[] parts = input.split(":", 2);
      String mode = parts[0];
      String rest = parts[1];

      TestNexusServices.TestNexusService1 stub =
          Workflow.newNexusServiceStub(
              TestNexusServices.TestNexusService1.class,
              NexusServiceOptions.newBuilder()
                  .setOperationOptions(
                      NexusOperationOptions.newBuilder()
                          .setScheduleToCloseTimeout(Duration.ofSeconds(30))
                          .build())
                  .build());

      switch (mode) {
        case "twoSync":
          {
            String r1 = stub.operation(MODE_SIGNAL_WITH_START + ":" + rest);
            String r2 = stub.operation(MODE_SIGNAL + ":" + rest);
            return r1 + "|" + r2;
          }
        case MODE_MULTI_SIGNAL_WITH_START:
          return stub.operation(MODE_MULTI_SIGNAL_WITH_START + ":" + rest);
        case MODE_ASYNC_SIGNAL_WITH_START:
          {
            NexusOperationHandle<String> h =
                Workflow.startNexusOperation(
                    stub::operation, MODE_ASYNC_SIGNAL_WITH_START + ":" + rest);
            // Wait for the async op to be Started (the event that carries the response link) but
            // not for its eventual result — the async op completes outside this workflow.
            h.getExecution().get();
            return "async-started";
          }
        default:
          throw new IllegalArgumentException("unknown mode: " + mode);
      }
    }
  }

  /** Callee workflow. Awaits {@code expectedSignals} signals then returns their joined payloads. */
  @WorkflowInterface
  public interface SignalCalleeWorkflow {
    @WorkflowMethod
    String execute(int expectedSignals);

    @SignalMethod
    void ping(String msg);
  }

  public static class SignalCalleeWorkflowImpl implements SignalCalleeWorkflow {
    private final List<String> received = new ArrayList<>();

    @Override
    public String execute(int expectedSignals) {
      Workflow.await(() -> received.size() >= expectedSignals);
      return String.join(",", received);
    }

    @Override
    public void ping(String msg) {
      received.add(msg);
    }
  }

  // ── Nexus service ────────────────────────────────────────────────────────────────────────

  /**
   * Single Nexus operation that dispatches based on a mode prefix in its input. Supports sync and
   * async return shapes.
   */
  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public static class SignalingNexusServiceImpl {

    @OperationImpl
    public OperationHandler<String, String> operation() {
      return new OperationHandler<String, String>() {
        @Override
        public OperationStartResult<String> start(
            OperationContext ctx, OperationStartDetails details, @Nullable String input) {
          String[] parts = input.split(":", 2);
          String mode = parts[0];
          String rest = parts[1];

          io.temporal.nexus.NexusOperationContext opCtx = Nexus.getOperationContext();
          WorkflowClient client = opCtx.getWorkflowClient();
          String taskQueue = opCtx.getInfo().getTaskQueue();

          switch (mode) {
            case MODE_SIGNAL_WITH_START:
              signalWithStart(client, rest, taskQueue, /* expectedSignals= */ 2, "first");
              return OperationStartResult.sync("ok:" + MODE_SIGNAL_WITH_START);
            case MODE_SIGNAL:
              client.newWorkflowStub(SignalCalleeWorkflow.class, rest).ping("second");
              return OperationStartResult.sync("ok:" + MODE_SIGNAL);
            case MODE_MULTI_SIGNAL_WITH_START:
              for (String id : rest.split(",")) {
                signalWithStart(client, id, taskQueue, /* expectedSignals= */ 1, "multi-signal");
              }
              return OperationStartResult.sync("ok:multi:" + rest);
            case MODE_ASYNC_SIGNAL_WITH_START:
              signalWithStart(client, rest, taskQueue, /* expectedSignals= */ 1, "async-signal");
              // Async branch in NexusTaskHandlerImpl. The caller never waits for completion, so
              // the token is opaque.
              return OperationStartResult.async("async-op-" + UUID.randomUUID());
            default:
              throw new IllegalArgumentException("unknown mode: " + mode);
          }
        }

        @Override
        public void cancel(OperationContext ctx, OperationCancelDetails details) {
          // Not exercised in these tests.
        }
      };
    }

    private static void signalWithStart(
        WorkflowClient client,
        String calleeWorkflowId,
        String taskQueue,
        int expectedSignals,
        String signalPayload) {
      SignalCalleeWorkflow startStub =
          client.newWorkflowStub(
              SignalCalleeWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setWorkflowId(calleeWorkflowId)
                  .setTaskQueue(taskQueue)
                  .build());
      BatchRequest batch = client.newSignalWithStartRequest();
      batch.add(startStub::execute, expectedSignals);
      batch.add(startStub::ping, signalPayload);
      client.signalWithStart(batch);
    }
  }
}
