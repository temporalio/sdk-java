package io.temporal.workflow.nexus;

import static org.junit.Assume.assumeTrue;

import io.nexusrpc.Operation;
import io.nexusrpc.Service;
import io.nexusrpc.handler.HandlerException;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.api.common.v1.Link;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.QueryRejectCondition;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowTargetOptions;
import io.temporal.failure.NexusOperationFailure;
import io.temporal.nexus.Nexus;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.NexusOperationOptions;
import io.temporal.workflow.NexusServiceOptions;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;

/**
 * A Nexus operation backed by a workflow Query. A Query is always synchronous and writes nothing to
 * history, so the handler simply queries and returns the result; there is no operation token and no
 * completion callback.
 *
 * <p>Covers the value round trip plus the failure modes a caller can observe: an unknown workflow,
 * a query handler that throws, and a Query rejected by the client's reject condition. All of these
 * must fail the caller's Nexus operation rather than hanging or returning a default.
 */
public class QueryOperationTest {

  private static final int BUMPS = 2;

  @BeforeClass
  public static void requireExternalService() {
    assumeTrue(
        "query response links require a real server that populates QueryWorkflowResponse.link",
        SDKTestWorkflowRule.useExternalService);
  }

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(QueryCallerWorkflowImpl.class, CounterWorkflowImpl.class)
          .setNexusServiceImplementation(new QueryingNexusServiceImpl())
          // The workflow being queried parks on a signal, so time skipping would fast-forward it
          // into its execution timeout and it would be gone before the Query lands.
          .setUseTimeskipping(false)
          // Matches the reject condition the Go test passes per request; in Java the condition is a
          // client-level option.
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setQueryRejectCondition(QueryRejectCondition.QUERY_REJECT_CONDITION_NOT_OPEN)
                  .build())
          .build();

  @Test
  public void queryOperationReturnsResult() {
    String targetWorkflowId = startCounterWorkflow();
    bumpCounter(targetWorkflowId, BUMPS);

    QueryCallerWorkflow caller =
        testWorkflowRule.newWorkflowStubTimeoutOptions(QueryCallerWorkflow.class, "query-caller");
    Assert.assertEquals(
        "the operation should return what the query handler computed from workflow state",
        BUMPS,
        caller.execute(new QueryRequest(targetWorkflowId, "", false)));

    completeCounterWorkflow(targetWorkflowId);
  }

  /**
   * End-to-end response link check: the server attaches a link to {@code QueryWorkflowResponse},
   * {@code RootWorkflowClientInvoker.query} hands it to the Nexus operation context, and the SDK
   * puts it on the caller's {@code NexusOperationCompleted} event.
   *
   * <p>Only the response direction is asserted. A Query writes nothing to the queried workflow's
   * history, so there is no event on the callee side to carry a forward link — unlike the signal
   * case in {@link SignalOperationLinkingTest}.
   */
  @Test
  public void queryOperationCapturesResponseLink() {
    String targetWorkflowId = startCounterWorkflow();
    bumpCounter(targetWorkflowId, BUMPS);

    QueryCallerWorkflow caller =
        testWorkflowRule.newWorkflowStubTimeoutOptions(QueryCallerWorkflow.class, "link-caller");
    Assert.assertEquals(BUMPS, caller.execute(new QueryRequest(targetWorkflowId, "", false)));

    String callerWorkflowId = WorkflowStub.fromTyped(caller).getExecution().getWorkflowId();
    History callerHistory =
        testWorkflowRule.getWorkflowClient().fetchHistory(callerWorkflowId).getHistory();

    List<HistoryEvent> completedEvents =
        getAllEventsOfType(callerHistory, EventType.EVENT_TYPE_NEXUS_OPERATION_COMPLETED);
    Assert.assertEquals(
        "expected exactly one NexusOperationCompleted event", 1, completedEvents.size());
    assertQueryResponseLink(completedEvents.get(0), targetWorkflowId);

    completeCounterWorkflow(targetWorkflowId);
  }

  @Test
  public void queryOnUnknownWorkflowFailsOperation() {
    QueryCallerWorkflow caller =
        testWorkflowRule.newWorkflowStubTimeoutOptions(
            QueryCallerWorkflow.class, "unknown-wid-caller");

    assertOperationFailedWith(
        HandlerException.ErrorType.NOT_FOUND,
        () -> caller.execute(new QueryRequest("unknown-wid-" + UUID.randomUUID(), "", false)));
  }

  @Test
  public void queryOnUnknownRunFailsOperation() {
    String targetWorkflowId = startCounterWorkflow();
    QueryCallerWorkflow caller =
        testWorkflowRule.newWorkflowStubTimeoutOptions(
            QueryCallerWorkflow.class, "unknown-rid-caller");

    assertOperationFailedWith(
        HandlerException.ErrorType.NOT_FOUND,
        () ->
            caller.execute(
                new QueryRequest(targetWorkflowId, UUID.randomUUID().toString(), false)));

    completeCounterWorkflow(targetWorkflowId);
  }

  @Test
  public void failedQueryFailsOperation() {
    String targetWorkflowId = startCounterWorkflow();
    QueryCallerWorkflow caller =
        testWorkflowRule.newWorkflowStubTimeoutOptions(
            QueryCallerWorkflow.class, "failed-query-caller");

    assertOperationFailedWith(
        HandlerException.ErrorType.BAD_REQUEST,
        () -> caller.execute(new QueryRequest(targetWorkflowId, "", true)));

    completeCounterWorkflow(targetWorkflowId);
  }

  @Test
  public void rejectedQueryFailsOperation() {
    // The reject condition is NOT_OPEN, so querying a workflow that has already closed is rejected
    // and must surface as an operation failure.
    String targetWorkflowId = startCounterWorkflow();
    completeCounterWorkflow(targetWorkflowId);

    QueryCallerWorkflow caller =
        testWorkflowRule.newWorkflowStubTimeoutOptions(
            QueryCallerWorkflow.class, "rejected-query-caller");

    assertOperationFailedWith(
        HandlerException.ErrorType.BAD_REQUEST,
        () -> caller.execute(new QueryRequest(targetWorkflowId, "", false)));
  }

  // ── helpers ──────────────────────────────────────────────────────────────────────────────

  /**
   * Asserts the caller's operation failed, and that it failed with the specific handler error type
   * the SDK is supposed to derive from what the handler threw. Asserting only {@code
   * NexusOperationFailure} would still pass if every failure collapsed into one retryable type, so
   * the mapping in {@code NexusTaskHandlerImpl.convertKnownFailures} is pinned here.
   */
  private static void assertOperationFailedWith(
      HandlerException.ErrorType expectedErrorType, ThrowingRunnable callerInvocation) {
    WorkflowFailedException e =
        Assert.assertThrows(WorkflowFailedException.class, callerInvocation);
    Assert.assertTrue(
        "expected the caller to fail with a NexusOperationFailure but got: " + e.getCause(),
        e.getCause() instanceof NexusOperationFailure);

    Throwable handlerFailure = e.getCause().getCause();
    Assert.assertTrue(
        "expected a HandlerException under the NexusOperationFailure but got: " + handlerFailure,
        handlerFailure instanceof HandlerException);
    Assert.assertEquals(expectedErrorType, ((HandlerException) handlerFailure).getErrorType());
  }

  /**
   * Assert that a caller-side event carries a response link naming the queried workflow. A Query
   * produces no history event, so the server answers with a {@code Link.Workflow} identifying the
   * execution that processed the Query rather than the {@code Link.WorkflowEvent} the signal and
   * update paths use.
   */
  private static void assertQueryResponseLink(HistoryEvent event, String queriedWorkflowId) {
    Assert.assertTrue(
        "expected a query response link on " + event.getEventType().name(),
        event.getLinksCount() >= 1);
    Link link = event.getLinks(0);
    Assert.assertTrue(
        "a Query link must use the Workflow variant, not WorkflowEvent, because a Query writes"
            + " nothing to history; got: "
            + link,
        link.hasWorkflow());
    Assert.assertEquals(
        "the response link should name the queried workflow",
        queriedWorkflowId,
        link.getWorkflow().getWorkflowId());
    Assert.assertFalse(
        "the response link should name the run that processed the Query",
        link.getWorkflow().getRunId().isEmpty());
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

  private String startCounterWorkflow() {
    String workflowId = "counter-" + UUID.randomUUID();
    WorkflowStub stub =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub(
                "CounterWorkflow",
                WorkflowOptions.newBuilder()
                    .setWorkflowId(workflowId)
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .build());
    stub.start();
    return workflowId;
  }

  private void bumpCounter(String workflowId, int times) {
    CounterWorkflow stub =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(CounterWorkflow.class, workflowId);
    for (int i = 0; i < times; i++) {
      stub.bump();
    }
  }

  private void completeCounterWorkflow(String workflowId) {
    WorkflowStub stub = testWorkflowRule.getWorkflowClient().newUntypedWorkflowStub(workflowId);
    stub.signal("done");
    stub.getResult(Integer.class);
  }

  // ── workflows ────────────────────────────────────────────────────────────────────────────

  /** Target of the Query: holds a counter that signals advance and a query reads. */
  @WorkflowInterface
  public interface CounterWorkflow {
    @WorkflowMethod
    int execute();

    @QueryMethod
    int getCount(boolean fail);

    @SignalMethod
    void bump();

    @SignalMethod
    void done();
  }

  public static class CounterWorkflowImpl implements CounterWorkflow {
    private int counter;
    private boolean completed;

    @Override
    public int execute() {
      Workflow.await(() -> completed);
      return counter;
    }

    @Override
    public int getCount(boolean fail) {
      if (fail) {
        // A query handler that throws makes the server answer with a query failure, which the
        // handler surfaces to the caller as a failed operation.
        throw new IllegalStateException("query failed (for testing)");
      }
      return counter;
    }

    @Override
    public void bump() {
      counter++;
    }

    @Override
    public void done() {
      completed = true;
    }
  }

  @WorkflowInterface
  public interface QueryCallerWorkflow {
    @WorkflowMethod
    int execute(QueryRequest request);
  }

  public static class QueryCallerWorkflowImpl implements QueryCallerWorkflow {
    @Override
    public int execute(QueryRequest request) {
      TestNexusQueryService service =
          Workflow.newNexusServiceStub(
              TestNexusQueryService.class,
              NexusServiceOptions.newBuilder()
                  .setOperationOptions(
                      NexusOperationOptions.newBuilder()
                          .setScheduleToCloseTimeout(Duration.ofSeconds(20))
                          .build())
                  .build());
      return service.query(request);
    }
  }

  // ── nexus service ────────────────────────────────────────────────────────────────────────

  @Service
  public interface TestNexusQueryService {
    @Operation
    Integer query(QueryRequest input);
  }

  @ServiceImpl(service = TestNexusQueryService.class)
  public static class QueryingNexusServiceImpl {
    @OperationImpl
    public OperationHandler<QueryRequest, Integer> query() {
      // A Query resolves immediately, so this is a plain synchronous operation: no operation token,
      // no completion callback, nothing to cancel.
      return OperationHandler.sync(
          (context, details, input) -> {
            WorkflowClient client = Nexus.getOperationContext().getWorkflowClient();
            WorkflowTargetOptions.Builder target =
                WorkflowTargetOptions.newBuilder().setWorkflowId(input.getWorkflowId());
            if (!input.getRunId().isEmpty()) {
              target.setRunId(input.getRunId());
            }
            return client
                .newWorkflowStub(CounterWorkflow.class, target.build())
                .getCount(input.isFail());
          });
    }
  }

  /** Input describing which workflow to query and how the query should behave. */
  public static final class QueryRequest {
    private String workflowId;
    private String runId;
    private boolean fail;

    public QueryRequest() {}

    QueryRequest(String workflowId, String runId, boolean fail) {
      this.workflowId = workflowId;
      this.runId = runId;
      this.fail = fail;
    }

    public String getWorkflowId() {
      return workflowId;
    }

    public String getRunId() {
      return runId;
    }

    public boolean isFail() {
      return fail;
    }
  }
}
