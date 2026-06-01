package io.temporal.workflow.nexus;

import static org.junit.Assume.assumeTrue;

import com.google.protobuf.util.Durations;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
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
import io.temporal.api.workflowservice.v1.RegisterNamespaceRequest;
import io.temporal.client.BatchRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.nexus.Nexus;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
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
import org.junit.After;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

/**
 * Verifies link propagation in both directions when a Nexus operation handler interacts with a
 * workflow via signal. Covers four scenarios:
 *
 * <ul>
 *   <li>{@link #testSignalOperationLinks()} — same-namespace, sync handler, two signals
 *       (signalWithStart + plain signal).
 *   <li>{@link #testCrossNamespaceSignalOperationLinks()} — caller and callee in different
 *       namespaces; otherwise identical to the same-namespace case.
 *   <li>{@link #testMultiSignalOperationLinks()} — one Nexus operation signals three different
 *       callees; verifies all three backlinks land on the caller's single {@code
 *       NexusOperationCompleted} event.
 *   <li>{@link #testAsyncSignalOperationLinks()} — handler returns an async result after signaling;
 *       verifies the backlink lands on {@code NexusOperationStarted} (the async response path in
 *       {@link io.temporal.internal.nexus.NexusTaskHandlerImpl}).
 * </ul>
 *
 * <p>All four tests require Temporal server &ge; 1.31 with {@code EnableCHASMSignalBacklinks=true};
 * the in-memory test server does not implement this path so the class is skipped unless a real
 * server is in use.
 */
public class SignalOperationLinkingTest extends BaseNexusTest {

  private static final String MODE_SIGNAL_WITH_START = "signalWithStart";
  private static final String MODE_SIGNAL = "signal";
  private static final String MODE_MULTI_SIGNAL_WITH_START = "multi";
  private static final String MODE_ASYNC_SIGNAL_WITH_START = "asyncSignalWithStart";
  private static final String CALLEE_NAMESPACE = "UnitTest2";

  // Caller workflow + Nexus handler register here (namespace UnitTest).
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(SignalCallerWorkflow.class, SignalCalleeWorkflowImpl.class)
          .setNexusServiceImplementation(new SignalingNexusServiceImpl())
          .build();

  // Separate worker/client on the callee namespace, used by the cross-namespace test. No
  // precedent in the repo for multi-@Rule SDKTestWorkflowRule patterns; every test method pays
  // the cost of starting this second worker even if it doesn't use it. Acceptable for the
  // current test count; revisit if more cross-namespace tests get added.
  @Rule
  public SDKTestWorkflowRule calleeNamespaceRule =
      SDKTestWorkflowRule.newBuilder()
          .setNamespace(CALLEE_NAMESPACE)
          .setWorkflowTypes(SignalCalleeWorkflowImpl.class)
          .build();

  @BeforeClass
  public static void requireExternalServiceAndSetupCalleeNamespace() {
    // The server-side backlink implementation (temporalio/temporal#9897) is gated by
    // EnableCHASMSignalBacklinks and is only present in real servers.
    assumeTrue(
        "signal backlinks require a real server with EnableCHASMSignalBacklinks=true",
        SDKTestWorkflowRule.useExternalService);
    // The test rule does not auto-register namespaces on an external server.
    ensureNamespaceExists(CALLEE_NAMESPACE);
  }

  @After
  public void resetNamespaceOverrides() {
    SignalingNexusServiceImpl.calleeNamespaceOverride = null;
    SignalingNexusServiceImpl.calleeTaskQueueOverride = null;
  }

  @Override
  protected SDKTestWorkflowRule getTestWorkflowRule() {
    return testWorkflowRule;
  }

  // ── Tests ────────────────────────────────────────────────────────────────────────────────

  @Test
  public void testSignalOperationLinks() {
    runTwoSignalScenario(testWorkflowRule);
  }

  @Test
  public void testCrossNamespaceSignalOperationLinks() {
    SignalingNexusServiceImpl.calleeNamespaceOverride = CALLEE_NAMESPACE;
    SignalingNexusServiceImpl.calleeTaskQueueOverride = calleeNamespaceRule.getTaskQueue();
    runTwoSignalScenario(calleeNamespaceRule);
  }

  /**
   * One Nexus operation signals three different callees. The handler's three signal-class RPCs each
   * contribute a backlink and all three end up on the caller's single {@code
   * NexusOperationCompleted} event.
   */
  @Test
  public void testMultiSignalOperationLinks() {
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    List<String> calleeIds =
        Arrays.asList(
            "multi-callee-a-" + UUID.randomUUID(),
            "multi-callee-b-" + UUID.randomUUID(),
            "multi-callee-c-" + UUID.randomUUID());

    TestWorkflows.TestWorkflow1 callerStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class, "caller");
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

    // Callee → caller: the single NexusOperationCompleted carries one backlink per callee.
    List<HistoryEvent> completedEvents =
        getAllEventsOfType(callerHistory, EventType.EVENT_TYPE_NEXUS_OPERATION_COMPLETED);
    Assert.assertEquals(
        "expected exactly one NexusOperationCompleted event", 1, completedEvents.size());
    HistoryEvent completed = completedEvents.get(0);
    Assert.assertEquals(
        "expected one backlink per signaled callee", calleeIds.size(), completed.getLinksCount());
    List<String> backlinkWorkflowIds = new ArrayList<>();
    for (int i = 0; i < completed.getLinksCount(); i++) {
      io.temporal.api.common.v1.Link.WorkflowEvent backlink =
          completed.getLinks(i).getWorkflowEvent();
      backlinkWorkflowIds.add(backlink.getWorkflowId());
      EventType backlinkEventType =
          backlink.hasRequestIdRef()
              ? backlink.getRequestIdRef().getEventType()
              : backlink.getEventRef().getEventType();
      Assert.assertEquals(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED, backlinkEventType);
    }
    Assert.assertTrue(
        "expected backlinks to reference all three callees: " + backlinkWorkflowIds,
        backlinkWorkflowIds.containsAll(calleeIds));
  }

  /**
   * Async response path: handler signals the callee then returns an async result. Verifies that the
   * backlink lands on {@code NexusOperationStarted} (the async branch in NexusTaskHandlerImpl).
   */
  @Test
  public void testAsyncSignalOperationLinks() {
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    String calleeWorkflowId = "async-callee-" + UUID.randomUUID();

    TestWorkflows.TestWorkflow1 callerStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class, "caller");
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
    assertBacklink(startedEvents.get(0), calleeWorkflowId);
  }

  // ── Shared scenario + assertion helpers ──────────────────────────────────────────────────

  /**
   * Drive the two-signal flow (signalWithStart + plain signal) and assert link propagation. Used by
   * same-namespace and cross-namespace tests; the only thing that varies is which rule's client
   * fetches the callee history.
   */
  private void runTwoSignalScenario(SDKTestWorkflowRule calleeRule) {
    WorkflowClient callerClient = testWorkflowRule.getWorkflowClient();
    WorkflowClient calleeClient = calleeRule.getWorkflowClient();
    String calleeWorkflowId = "signal-callee-" + UUID.randomUUID();

    TestWorkflows.TestWorkflow1 callerStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class, "caller");
    String result = callerStub.execute("twoSync:" + calleeWorkflowId);
    Assert.assertEquals("ok:signalWithStart|ok:signal", result);

    String calleeResult =
        calleeClient.newUntypedWorkflowStub(calleeWorkflowId).getResult(String.class);
    Assert.assertEquals("first,second", calleeResult);

    String callerWorkflowId = WorkflowStub.fromTyped(callerStub).getExecution().getWorkflowId();
    History callerHistory = callerClient.fetchHistory(callerWorkflowId).getHistory();
    History calleeHistory = calleeClient.fetchHistory(calleeWorkflowId).getHistory();

    assertForwardLinks(calleeHistory, callerWorkflowId, /* expectedCount= */ 2);

    List<HistoryEvent> completedEvents =
        getAllEventsOfType(callerHistory, EventType.EVENT_TYPE_NEXUS_OPERATION_COMPLETED);
    Assert.assertEquals(
        "expected two NexusOperationCompleted events on the caller", 2, completedEvents.size());
    for (HistoryEvent completed : completedEvents) {
      assertBacklink(completed, calleeWorkflowId);
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
   * NexusOperationStarted}) carries a backlink to the callee's {@code WorkflowExecutionSignaled}
   * event. Server PR #9897 keys these via {@code RequestIdReference} rather than {@code
   * EventReference}, so we accept either oneof variant.
   */
  private static void assertBacklink(HistoryEvent event, String calleeWorkflowId) {
    Assert.assertTrue(
        "expected a signal-event backlink on " + event.getEventType().name(),
        event.getLinksCount() >= 1);
    io.temporal.api.common.v1.Link.WorkflowEvent backlink = event.getLinks(0).getWorkflowEvent();
    Assert.assertEquals(calleeWorkflowId, backlink.getWorkflowId());
    EventType backlinkEventType =
        backlink.hasRequestIdRef()
            ? backlink.getRequestIdRef().getEventType()
            : backlink.getEventRef().getEventType();
    Assert.assertEquals(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED, backlinkEventType);
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
   * Register {@code namespace} on the external server if it doesn't already exist. Honours the
   * {@code TEMPORAL_SERVICE_ADDRESS} env var the same way {@code
   * io.temporal.testing.internal.ExternalServiceTestConfigurator} does, so the test works against
   * whichever server the test rule itself connects to.
   */
  private static void ensureNamespaceExists(String namespace) {
    String target = System.getenv("TEMPORAL_SERVICE_ADDRESS");
    WorkflowServiceStubsOptions.Builder optionsBuilder = WorkflowServiceStubsOptions.newBuilder();
    if (target != null && !target.isEmpty()) {
      optionsBuilder.setTarget(target);
    }
    WorkflowServiceStubs stubs = WorkflowServiceStubs.newServiceStubs(optionsBuilder.build());
    try {
      stubs
          .blockingStub()
          .registerNamespace(
              RegisterNamespaceRequest.newBuilder()
                  .setNamespace(namespace)
                  .setWorkflowExecutionRetentionPeriod(Durations.fromHours(24))
                  .build());
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() != Status.Code.ALREADY_EXISTS) {
        throw e;
      }
    } finally {
      stubs.shutdownNow();
    }
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
                  .setEndpoint(getEndpointName())
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
            // Wait for the async op to be Started (the event that carries the backlink) but
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
   * async return shapes and an optional namespace override for cross-namespace tests.
   */
  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public static class SignalingNexusServiceImpl {
    static volatile String calleeNamespaceOverride;
    static volatile String calleeTaskQueueOverride;

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
          WorkflowClient ambient = opCtx.getWorkflowClient();
          WorkflowClient calleeClient =
              calleeNamespaceOverride == null
                  ? ambient
                  : WorkflowClient.newInstance(
                      ambient.getWorkflowServiceStubs(),
                      WorkflowClientOptions.newBuilder()
                          .setNamespace(calleeNamespaceOverride)
                          .build());
          String taskQueue =
              calleeTaskQueueOverride != null
                  ? calleeTaskQueueOverride
                  : opCtx.getInfo().getTaskQueue();

          switch (mode) {
            case MODE_SIGNAL_WITH_START:
              signalWithStart(calleeClient, rest, taskQueue, /* expectedSignals= */ 2, "first");
              return OperationStartResult.sync("ok:" + MODE_SIGNAL_WITH_START);
            case MODE_SIGNAL:
              calleeClient.newWorkflowStub(SignalCalleeWorkflow.class, rest).ping("second");
              return OperationStartResult.sync("ok:" + MODE_SIGNAL);
            case MODE_MULTI_SIGNAL_WITH_START:
              for (String id : rest.split(",")) {
                signalWithStart(
                    calleeClient, id, taskQueue, /* expectedSignals= */ 1, "multi-signal");
              }
              return OperationStartResult.sync("ok:multi:" + rest);
            case MODE_ASYNC_SIGNAL_WITH_START:
              signalWithStart(
                  calleeClient, rest, taskQueue, /* expectedSignals= */ 1, "async-signal");
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
