package io.temporal.internal.payload.visitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.RecordMarkerCommandAttributes;
import io.temporal.api.command.v1.ScheduleActivityTaskCommandAttributes;
import io.temporal.api.command.v1.ScheduleNexusOperationCommandAttributes;
import io.temporal.api.command.v1.StartChildWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.UpsertWorkflowSearchAttributesCommandAttributes;
import io.temporal.api.common.v1.Header;
import io.temporal.api.common.v1.Memo;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.failure.v1.ApplicationFailureInfo;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.protocol.v1.Message;
import io.temporal.api.query.v1.WorkflowQueryResult;
import io.temporal.api.workflowservice.v1.CountWorkflowExecutionsResponse;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class PayloadVisitorTest {

  static Payload p(String s) {
    return Payload.newBuilder().setData(ByteString.copyFromUtf8(s)).build();
  }

  static String data(Payload p) {
    return p.getData().toStringUtf8();
  }

  static Payloads payloads(String... values) {
    Payloads.Builder b = Payloads.newBuilder();
    for (String v : values) {
      b.addPayloads(p(v));
    }
    return b.build();
  }

  /**
   * Records every payload seen (in order) and the number of visit calls, leaving payloads
   * unchanged.
   */
  static final class CollectingVisitor implements PayloadVisitor<Object> {
    final List<String> seen = Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger visits = new AtomicInteger();

    @Override
    public List<Payload> visit(Object ctx, List<Payload> payloads) {
      visits.incrementAndGet();
      for (Payload p : payloads) {
        seen.add(data(p));
      }
      return payloads;
    }
  }

  static PayloadVisitorOptions<Object> options(PayloadVisitor<Object> visitor) {
    return PayloadVisitorOptions.newBuilder(visitor).build();
  }

  static Command activity(String activityId, Payloads input) {
    return Command.newBuilder()
        .setCommandType(CommandType.COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK)
        .setScheduleActivityTaskCommandAttributes(
            ScheduleActivityTaskCommandAttributes.newBuilder()
                .setActivityId(activityId)
                .setInput(input))
        .build();
  }

  /** Executor supplied to the concurrent visits (unused by the single-threaded tests). */
  private ExecutorService executor;

  @Before
  public void setUpExecutor() {
    executor = Executors.newCachedThreadPool();
  }

  @After
  public void tearDownExecutor() {
    executor.shutdownNow();
  }

  @Test
  public void visitsAndMutatesAllPayloads() {
    RespondWorkflowTaskCompletedRequest request =
        RespondWorkflowTaskCompletedRequest.newBuilder()
            .addCommands(activity("a", payloads("one", "two")))
            .addCommands(activity("b", payloads("three")))
            .build();

    CollectingVisitor counter = new CollectingVisitor();
    RespondWorkflowTaskCompletedRequest unchanged =
        PayloadVisitors.visit(request, options(counter));
    assertEquals(java.util.Arrays.asList("one", "two", "three"), counter.seen);
    // Two Payloads sequences (one per command's input): two visits, three payloads.
    assertEquals(2, counter.visits.get());
    assertEquals(request, unchanged);

    // Mutating: uppercase every payload's data.
    RespondWorkflowTaskCompletedRequest mutated =
        PayloadVisitors.visit(
            request,
            options(
                (ctx, pls) ->
                    pls.stream()
                        .map(
                            p ->
                                p.toBuilder()
                                    .setData(ByteString.copyFromUtf8(data(p).toUpperCase()))
                                    .build())
                        .collect(Collectors.toList())));
    assertEquals(
        payloads("ONE", "TWO"),
        mutated.getCommands(0).getScheduleActivityTaskCommandAttributes().getInput());
    assertEquals(
        payloads("THREE"),
        mutated.getCommands(1).getScheduleActivityTaskCommandAttributes().getInput());
  }

  @Test
  public void visitsSinglePayloadField() {
    Command command =
        Command.newBuilder()
            .setScheduleNexusOperationCommandAttributes(
                ScheduleNexusOperationCommandAttributes.newBuilder().setInput(p("nexus")))
            .build();

    CollectingVisitor counter = new CollectingVisitor();
    Command result = PayloadVisitors.visit(command, options(counter));
    assertEquals(Collections.singletonList("nexus"), counter.seen);

    // A single-payload field can be replaced with one payload.
    Command observed =
        PayloadVisitors.visit(
            command, options((ctx, pls) -> Collections.singletonList(p("replaced"))));
    assertEquals(
        "replaced",
        observed.getScheduleNexusOperationCommandAttributes().getInput().getData().toStringUtf8());
    assertEquals(Collections.singletonList("nexus"), counter.seen);
    assertEquals(command, result);
  }

  @Test
  public void singlePayloadFieldRequiresExactlyOnePayload() {
    Command command =
        Command.newBuilder()
            .setScheduleNexusOperationCommandAttributes(
                ScheduleNexusOperationCommandAttributes.newBuilder().setInput(p("nexus")))
            .build();

    // Returning zero payloads for a single-payload field is rejected.
    assertThrows(
        IllegalStateException.class,
        () -> PayloadVisitors.visit(command, options((ctx, pls) -> Collections.emptyList())));

    // Returning more than one payload for a single-payload field is rejected.
    assertThrows(
        IllegalStateException.class,
        () ->
            PayloadVisitors.visit(
                command, options((ctx, pls) -> java.util.Arrays.asList(p("a"), p("b")))));
  }

  @Test
  public void visitsMapOfPayloads() {
    Command command =
        Command.newBuilder()
            .setUpsertWorkflowSearchAttributesCommandAttributes(
                UpsertWorkflowSearchAttributesCommandAttributes.newBuilder()
                    .setSearchAttributes(
                        SearchAttributes.newBuilder()
                            .putIndexedFields("k1", p("v1"))
                            .putIndexedFields("k2", p("v2"))))
            .build();

    CollectingVisitor counter = new CollectingVisitor();
    PayloadVisitors.visit(command, options(counter));
    // A map<string, Payload> is visited once per entry; map iteration order is unspecified, so
    // assert the exact visit count and the value set rather than positional offsets.
    assertEquals(2, counter.visits.get());
    assertEquals(new HashSet<>(java.util.Arrays.asList("v1", "v2")), new HashSet<>(counter.seen));

    Command mutated =
        PayloadVisitors.visit(
            command, options((ctx, pls) -> Collections.singletonList(p(data(pls.get(0)) + "!"))));
    Map<String, Payload> fields =
        mutated
            .getUpsertWorkflowSearchAttributesCommandAttributes()
            .getSearchAttributes()
            .getIndexedFieldsMap();
    assertEquals("v1!", data(fields.get("k1")));
    assertEquals("v2!", data(fields.get("k2")));
  }

  @Test
  public void visitsMapOfPayloadsSequences() {
    Command command =
        Command.newBuilder()
            .setRecordMarkerCommandAttributes(
                RecordMarkerCommandAttributes.newBuilder()
                    .setMarkerName("m")
                    .putDetails("d1", payloads("x", "y")))
            .build();

    CollectingVisitor counter = new CollectingVisitor();
    PayloadVisitors.visit(command, options(counter));
    // A single map<string, Payloads> entry is one sequence: one visit, two payloads.
    assertEquals(1, counter.visits.get());
    assertEquals(java.util.Arrays.asList("x", "y"), counter.seen);
  }

  @Test
  public void visitsMapOfMessages() {
    // RespondWorkflowTaskCompletedRequest.query_results is map<string, WorkflowQueryResult>, whose
    // values carry payloads: exercises the map-of-messages path (rebuild value + write back).
    RespondWorkflowTaskCompletedRequest request =
        RespondWorkflowTaskCompletedRequest.newBuilder()
            .putQueryResults(
                "q1", WorkflowQueryResult.newBuilder().setAnswer(payloads("a")).build())
            .build();

    CollectingVisitor counter = new CollectingVisitor();
    PayloadVisitors.visit(request, options(counter));
    assertEquals(Collections.singletonList("a"), counter.seen);

    RespondWorkflowTaskCompletedRequest mutated =
        PayloadVisitors.visit(
            request, options((ctx, pls) -> Collections.singletonList(p(data(pls.get(0)) + "!"))));
    assertEquals(payloads("a!"), mutated.getQueryResultsMap().get("q1").getAnswer());
  }

  @Test
  public void visitsRepeatedPayloadField() {
    // CountWorkflowExecutionsResponse.AggregationGroup.group_values is a bare repeated Payload.
    CountWorkflowExecutionsResponse response =
        CountWorkflowExecutionsResponse.newBuilder()
            .addGroups(
                CountWorkflowExecutionsResponse.AggregationGroup.newBuilder()
                    .addGroupValues(p("g1"))
                    .addGroupValues(p("g2")))
            .build();

    CollectingVisitor counter = new CollectingVisitor();
    PayloadVisitors.visit(response, options(counter));
    // A repeated Payload is one sequence: one visit, two payloads.
    assertEquals(1, counter.visits.get());
    assertEquals(java.util.Arrays.asList("g1", "g2"), counter.seen);

    CountWorkflowExecutionsResponse mutated =
        PayloadVisitors.visit(
            response,
            options(
                (ctx, pls) ->
                    pls.stream().map(pl -> p(data(pl) + "!")).collect(Collectors.toList())));
    assertEquals("g1!", data(mutated.getGroups(0).getGroupValues(0)));
    assertEquals("g2!", data(mutated.getGroups(0).getGroupValues(1)));
  }

  @Test
  public void visitsPayloadsAsRoot() {
    Payloads root = payloads("a", "b");

    CollectingVisitor counter = new CollectingVisitor();
    Payloads unchanged = PayloadVisitors.visit(root, options(counter));
    // The repeated Payload inside Payloads is one sequence: one visit, two payloads.
    assertEquals(1, counter.visits.get());
    assertEquals(java.util.Arrays.asList("a", "b"), counter.seen);
    assertEquals(root, unchanged);

    Payloads mutated =
        PayloadVisitors.visit(root, options((ctx, pls) -> Collections.singletonList(p("x"))));
    assertEquals(payloads("x"), mutated);
  }

  @Test
  public void visitsBuilderInPlace() {
    RespondWorkflowTaskCompletedRequest.Builder builder =
        RespondWorkflowTaskCompletedRequest.newBuilder().addCommands(activity("a", payloads("x")));

    PayloadVisitors.visit(builder, options((ctx, pls) -> Collections.singletonList(p("y"))));

    assertEquals(
        payloads("y"),
        builder.getCommands(0).getScheduleActivityTaskCommandAttributes().getInput());
  }

  @Test
  public void visitCountDistinguishesSequencesFromMapEntries() {
    // A Memo with two fields is visited once per entry: two visits, two payloads.
    Memo memo = Memo.newBuilder().putFields("a", p("1")).putFields("b", p("2")).build();
    CollectingVisitor memoVisitor = new CollectingVisitor();
    PayloadVisitors.visit(memo, options(memoVisitor));
    // Memo fields are a map (unspecified order): assert visit count and the value set.
    assertEquals(2, memoVisitor.visits.get());
    assertEquals(new HashSet<>(java.util.Arrays.asList("1", "2")), new HashSet<>(memoVisitor.seen));

    // An activity command with two inputs is one Payloads sequence: one visit, two payloads.
    Command command =
        Command.newBuilder()
            .setScheduleActivityTaskCommandAttributes(
                ScheduleActivityTaskCommandAttributes.newBuilder().setInput(payloads("1", "2")))
            .build();
    CollectingVisitor inputVisitor = new CollectingVisitor();
    PayloadVisitors.visit(command, options(inputVisitor));
    // A Payloads sequence preserves order, so assert the exact ordered values.
    assertEquals(1, inputVisitor.visits.get());
    assertEquals(java.util.Arrays.asList("1", "2"), inputVisitor.seen);
  }

  @Test
  public void visitsHeaders() {
    Command command =
        Command.newBuilder()
            .setScheduleActivityTaskCommandAttributes(
                ScheduleActivityTaskCommandAttributes.newBuilder()
                    .setInput(payloads("in"))
                    .setHeader(Header.newBuilder().putFields("h", p("hv"))))
            .build();

    CollectingVisitor counter = new CollectingVisitor();
    PayloadVisitors.visit(command, options(counter));
    // With headers not skipped (the default), the header payload is visited too.
    assertEquals(new HashSet<>(java.util.Arrays.asList("in", "hv")), new HashSet<>(counter.seen));
  }

  @Test
  public void visitsSearchAttributes() {
    Command command =
        Command.newBuilder()
            .setStartChildWorkflowExecutionCommandAttributes(
                StartChildWorkflowExecutionCommandAttributes.newBuilder()
                    .setInput(payloads("in"))
                    .setSearchAttributes(
                        SearchAttributes.newBuilder().putIndexedFields("k", p("v"))))
            .build();

    CollectingVisitor counter = new CollectingVisitor();
    PayloadVisitors.visit(command, options(counter));
    // With search attributes not skipped (the default), the search attribute payload is visited.
    assertEquals(new HashSet<>(java.util.Arrays.asList("in", "v")), new HashSet<>(counter.seen));
  }

  @Test
  public void skipsHeaders() {
    Command command =
        Command.newBuilder()
            .setScheduleActivityTaskCommandAttributes(
                ScheduleActivityTaskCommandAttributes.newBuilder()
                    .setInput(payloads("in"))
                    .setHeader(Header.newBuilder().putFields("h", p("hv"))))
            .build();

    CollectingVisitor counter = new CollectingVisitor();
    PayloadVisitors.visit(
        command, PayloadVisitorOptions.newBuilder(counter).setSkipHeaders(true).build());
    // The header payload is skipped; other payloads are still visited.
    assertEquals(Collections.singletonList("in"), counter.seen);
  }

  @Test
  public void skipsSearchAttributes() {
    Command command =
        Command.newBuilder()
            .setStartChildWorkflowExecutionCommandAttributes(
                StartChildWorkflowExecutionCommandAttributes.newBuilder()
                    .setInput(payloads("in"))
                    .setSearchAttributes(
                        SearchAttributes.newBuilder().putIndexedFields("k", p("v"))))
            .build();

    CollectingVisitor counter = new CollectingVisitor();
    PayloadVisitors.visit(
        command, PayloadVisitorOptions.newBuilder(counter).setSkipSearchAttributes(true).build());
    // The search attribute payload is skipped; other payloads are still visited.
    assertEquals(Collections.singletonList("in"), counter.seen);
  }

  @Test
  public void contextScopesPerCommand() {
    RespondWorkflowTaskCompletedRequest request =
        RespondWorkflowTaskCompletedRequest.newBuilder()
            .addCommands(activity("a", payloads("act")))
            .addCommands(
                Command.newBuilder()
                    .setCommandType(CommandType.COMMAND_TYPE_START_CHILD_WORKFLOW_EXECUTION)
                    .setStartChildWorkflowExecutionCommandAttributes(
                        StartChildWorkflowExecutionCommandAttributes.newBuilder()
                            .setInput(payloads("child")))
                    .build())
            .build();

    // Default concurrency (1) visits the two repeated commands in declaration order.
    List<String> dataOrder = new ArrayList<>();
    List<CommandType> contextOrder = new ArrayList<>();
    PayloadVisitorOptions<CommandType> opts =
        PayloadVisitorOptions.<CommandType>newBuilder(
                (ctx, pls) -> {
                  for (Payload p : pls) {
                    dataOrder.add(data(p));
                    contextOrder.add(ctx);
                  }
                  return pls;
                })
            .setMessageVisitor(
                (current, msg) ->
                    msg instanceof Command.Builder
                        ? ((Command.Builder) msg).getCommandType()
                        : current)
            .build();

    PayloadVisitors.visit(request, opts);
    assertEquals(java.util.Arrays.asList("act", "child"), dataOrder);
    assertEquals(
        java.util.Arrays.asList(
            CommandType.COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK,
            CommandType.COMMAND_TYPE_START_CHILD_WORKFLOW_EXECUTION),
        contextOrder);
  }

  @Test
  public void initialContextUsedWhenNoMessageVisitor() {
    Command command =
        Command.newBuilder()
            .setScheduleActivityTaskCommandAttributes(
                ScheduleActivityTaskCommandAttributes.newBuilder().setInput(payloads("x")))
            .build();
    List<String> observed = new ArrayList<>();
    PayloadVisitorOptions<String> opts =
        PayloadVisitorOptions.<String>newBuilder(
                (ctx, pls) -> {
                  observed.add(ctx);
                  return pls;
                })
            .setInitialContext("root")
            .build();
    PayloadVisitors.visit(command, opts);
    assertEquals(Collections.singletonList("root"), observed);
  }

  @Test
  public void limitsStyleValidatorComposesBothSeams() {
    // The payload-limits feature is a read-only validator using both seams of PayloadVisitors:
    //   - per-payload (blob size) on the payload seam
    //   - per-message (e.g. memo field count) on the message seam
    int blobLimit = 8;
    int maxMemoFields = 2;

    PayloadVisitorOptions<Void> validator =
        PayloadVisitorOptions.<Void>newBuilder(
                (ctx, pls) -> {
                  for (Payload pl : pls) {
                    if (pl.getData().size() > blobLimit) {
                      throw new TestVisitorException("blob too large");
                    }
                  }
                  return pls; // read-only
                })
            .setMessageVisitor(
                (current, msg) -> {
                  if (msg instanceof Memo.Builder
                      && ((Memo.Builder) msg).getFieldsCount() > maxMemoFields) {
                    throw new TestVisitorException("too many memo fields");
                  }
                  return current;
                })
            .build();

    // Within both limits (small input, small memo): both seams run, neither trips.
    RespondWorkflowTaskCompletedRequest ok =
        RespondWorkflowTaskCompletedRequest.newBuilder()
            .addCommands(
                Command.newBuilder()
                    .setStartChildWorkflowExecutionCommandAttributes(
                        StartChildWorkflowExecutionCommandAttributes.newBuilder()
                            .setInput(payloads("small"))
                            .setMemo(
                                Memo.newBuilder().putFields("a", p("1")).putFields("b", p("2")))))
            .build();
    PayloadVisitors.visit(ok, validator);

    // Oversized blob trips the payload seam.
    RespondWorkflowTaskCompletedRequest bigBlob =
        RespondWorkflowTaskCompletedRequest.newBuilder()
            .addCommands(activity("a", payloads("way-too-large-payload")))
            .build();
    TestVisitorException blobError =
        assertThrows(TestVisitorException.class, () -> PayloadVisitors.visit(bigBlob, validator));
    assertEquals("blob too large", blobError.getMessage());

    // Too many memo fields trips the message seam (its payloads are individually small).
    RespondWorkflowTaskCompletedRequest bigMemo =
        RespondWorkflowTaskCompletedRequest.newBuilder()
            .addCommands(
                Command.newBuilder()
                    .setStartChildWorkflowExecutionCommandAttributes(
                        StartChildWorkflowExecutionCommandAttributes.newBuilder()
                            .setMemo(
                                Memo.newBuilder()
                                    .putFields("a", p("1"))
                                    .putFields("b", p("2"))
                                    .putFields("c", p("3")))))
            .build();
    TestVisitorException memoError =
        assertThrows(TestVisitorException.class, () -> PayloadVisitors.visit(bigMemo, validator));
    assertEquals("too many memo fields", memoError.getMessage());
  }

  @Test
  public void visitsNestedFailureCauses() {
    // Failure.cause is itself a Failure, so the visitor recurses into its own type; payloads at
    // each level of the cause chain must be visited.
    Failure failure =
        Failure.newBuilder()
            .setMessage("outer")
            .setApplicationFailureInfo(
                ApplicationFailureInfo.newBuilder().setDetails(payloads("d1")))
            .setCause(
                Failure.newBuilder()
                    .setMessage("inner")
                    .setApplicationFailureInfo(
                        ApplicationFailureInfo.newBuilder().setDetails(payloads("d2"))))
            .build();

    CollectingVisitor counter = new CollectingVisitor();
    PayloadVisitors.visit(failure, options(counter));
    assertEquals(2, counter.seen.size());
    assertTrue(counter.seen.contains("d1"));
    assertTrue(counter.seen.contains("d2"));
  }

  @Test
  public void roundTripsPayloadInsideAny() throws Exception {
    Memo memo = Memo.newBuilder().putFields("k", p("inside-any")).build();
    Message message = Message.newBuilder().setBody(Any.pack(memo)).build();

    CollectingVisitor counter = new CollectingVisitor();
    Message result = PayloadVisitors.visit(message, options(counter));
    assertEquals(Collections.singletonList("inside-any"), counter.seen);

    // Mutating through the Any re-packs correctly.
    Message mutated =
        PayloadVisitors.visit(
            message, options((ctx, pls) -> Collections.singletonList(p("changed"))));
    Memo unpacked = mutated.getBody().unpack(Memo.class);
    assertEquals("changed", data(unpacked.getFieldsMap().get("k")));
    // Unrelated content unchanged.
    assertEquals(result.getBody().getTypeUrl(), mutated.getBody().getTypeUrl());
  }

  @Test
  public void leavesUnknownAnyUntouched() throws Exception {
    // An Any whose type is not in the registry is left as-is.
    Message message =
        Message.newBuilder()
            .setBody(
                Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/some.unknown.Type")
                    .setValue(ByteString.copyFromUtf8("opaque")))
            .build();
    CollectingVisitor counter = new CollectingVisitor();
    Message result = PayloadVisitors.visit(message, options(counter));
    assertTrue(counter.seen.isEmpty());
    assertEquals(message, result);
  }

  @Test
  public void messageWithoutPayloadsReturnedUnchanged() {
    Command command =
        Command.newBuilder()
            .setCancelWorkflowExecutionCommandAttributes(
                io.temporal.api.command.v1.CancelWorkflowExecutionCommandAttributes.newBuilder())
            .build();
    CollectingVisitor counter = new CollectingVisitor();
    Command result = PayloadVisitors.visit(command, options(counter));
    assertTrue(counter.seen.isEmpty());
    assertEquals(command, result);
  }

  @Test
  public void propagatesVisitorError() {
    RespondWorkflowTaskCompletedRequest request =
        RespondWorkflowTaskCompletedRequest.newBuilder()
            .addCommands(activity("a", payloads("x")))
            .build();
    TestVisitorException boom = new TestVisitorException("boom");
    TestVisitorException thrown =
        assertThrows(
            TestVisitorException.class,
            () ->
                PayloadVisitors.visit(
                    request,
                    options(
                        (ctx, pls) -> {
                          throw boom;
                        })));
    assertSame(boom, thrown);
  }

  @Test
  public void messageVisitorErrorPropagates() {
    Command command =
        Command.newBuilder()
            .setScheduleActivityTaskCommandAttributes(
                ScheduleActivityTaskCommandAttributes.newBuilder().setInput(payloads("x")))
            .build();
    TestVisitorException boom = new TestVisitorException("message visitor boom");
    TestVisitorException thrown =
        assertThrows(
            TestVisitorException.class,
            () ->
                PayloadVisitors.visit(
                    command,
                    PayloadVisitorOptions.newBuilder((ctx, pls) -> pls)
                        .setMessageVisitor(
                            (current, msg) -> {
                              throw boom;
                            })
                        .build()));
    assertSame(boom, thrown);
  }

  @Test
  public void registryCoversPayloadBearingTypesAndExcludesOthers() {
    Map<String, MessageRegistryEntry> registry = GeneratedPayloadVisitor.REGISTRY;
    // Representative payload-bearing types must be present.
    for (String fullName :
        new String[] {
          "temporal.api.command.v1.Command",
          "temporal.api.command.v1.ScheduleActivityTaskCommandAttributes",
          "temporal.api.command.v1.RecordMarkerCommandAttributes",
          "temporal.api.failure.v1.Failure",
          "temporal.api.common.v1.Memo",
          "temporal.api.common.v1.Header",
          "temporal.api.common.v1.SearchAttributes",
          "temporal.api.common.v1.Payloads",
          "temporal.api.protocol.v1.Message",
          "temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest"
        }) {
      assertTrue("missing visitor for " + fullName, registry.containsKey(fullName));
    }
    // Types without reachable payloads must be excluded.
    assertFalse(registry.containsKey("temporal.api.common.v1.Payload"));
    assertFalse(registry.containsKey("temporal.api.common.v1.WorkflowExecution"));
    assertFalse(registry.containsKey("google.protobuf.DescriptorProto"));
  }

  @Test
  public void rejectsNullPayloadVisitor() {
    assertThrows(NullPointerException.class, () -> PayloadVisitorOptions.newBuilder(null));
  }

  @Test
  public void nullReturnFromVisitorFails() {
    Command command =
        Command.newBuilder()
            .setScheduleActivityTaskCommandAttributes(
                ScheduleActivityTaskCommandAttributes.newBuilder().setInput(payloads("x")))
            .build();
    assertThrows(
        IllegalStateException.class,
        () -> PayloadVisitors.visit(command, options((ctx, pls) -> null)));
  }

  // --- Concurrency and executor ---

  @Test
  public void rejectsConcurrencyBelowOne() {
    assertThrows(
        IllegalArgumentException.class,
        () -> PayloadVisitorOptions.newBuilder((ctx, pls) -> pls).setConcurrency(0).build());
  }

  @Test
  public void rejectsConcurrencyAboveOneWithoutExecutor() {
    assertThrows(
        IllegalArgumentException.class,
        () -> PayloadVisitorOptions.newBuilder((ctx, pls) -> pls).setConcurrency(2).build());
  }

  /**
   * A request with {@code n} activity commands, each carrying one distinct single-payload input.
   */
  static RespondWorkflowTaskCompletedRequest requestWithInputs(int n) {
    RespondWorkflowTaskCompletedRequest.Builder b =
        RespondWorkflowTaskCompletedRequest.newBuilder();
    for (int i = 0; i < n; i++) {
      b.addCommands(activity("a" + i, payloads("p" + i)));
    }
    return b.build();
  }

  @Test
  public void concurrencyEqualToWorkAllowsFullOverlap() throws Exception {
    int n = 4;
    RespondWorkflowTaskCompletedRequest request = requestWithInputs(n);
    CyclicBarrier barrier = new CyclicBarrier(n);

    // Each of the n visits must reach the barrier simultaneously, proving n concurrent visits.
    PayloadVisitors.visit(
        request,
        PayloadVisitorOptions.newBuilder(
                (ctx, pls) -> {
                  try {
                    barrier.await(5, TimeUnit.SECONDS);
                  } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
                    throw new RuntimeException(e);
                  }
                  return pls;
                })
            .setConcurrency(n)
            .setExecutor(executor)
            .build());
  }

  @Test
  public void boundedConcurrencyNeverExceedsLimit() {
    int n = 8;
    int limit = 3;
    RespondWorkflowTaskCompletedRequest request = requestWithInputs(n);

    AtomicInteger inFlight = new AtomicInteger();
    AtomicInteger maxInFlight = new AtomicInteger();

    PayloadVisitors.visit(
        request,
        PayloadVisitorOptions.newBuilder(
                (ctx, pls) -> {
                  int now = inFlight.incrementAndGet();
                  maxInFlight.accumulateAndGet(now, Math::max);
                  try {
                    Thread.sleep(20);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  inFlight.decrementAndGet();
                  return pls;
                })
            .setConcurrency(limit)
            .setExecutor(executor)
            .build());

    assertTrue(
        "max in-flight " + maxInFlight.get() + " > limit " + limit, maxInFlight.get() <= limit);
    assertTrue("expected some overlap, got " + maxInFlight.get(), maxInFlight.get() > 1);
  }

  @Test
  public void sequentialConcurrencyVisitsOneAtATimeInOrder() {
    int n = 5;
    RespondWorkflowTaskCompletedRequest request = requestWithInputs(n);

    AtomicInteger inFlight = new AtomicInteger();
    AtomicInteger maxInFlight = new AtomicInteger();
    List<String> order = new ArrayList<>();

    PayloadVisitors.visit(
        request,
        PayloadVisitorOptions.newBuilder(
                (ctx, pls) -> {
                  int now = inFlight.incrementAndGet();
                  maxInFlight.accumulateAndGet(now, Math::max);
                  order.add(pls.get(0).getData().toStringUtf8());
                  inFlight.decrementAndGet();
                  return pls;
                })
            .setConcurrency(1)
            .build());

    assertEquals(1, maxInFlight.get());
    List<String> expected = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      expected.add("p" + i);
    }
    assertEquals(expected, order);
  }

  @Test
  public void concurrentVisitorErrorPropagates() {
    RespondWorkflowTaskCompletedRequest request = requestWithInputs(8);
    TestVisitorException boom = new TestVisitorException("boom");
    TestVisitorException thrown =
        assertThrows(
            TestVisitorException.class,
            () ->
                PayloadVisitors.visit(
                    request,
                    PayloadVisitorOptions.newBuilder(
                            (ctx, pls) -> {
                              if (pls.get(0).getData().toStringUtf8().equals("p5")) {
                                throw boom;
                              }
                              return pls;
                            })
                        .setConcurrency(4)
                        .setExecutor(executor)
                        .build()));
    assertSame(boom, thrown);
  }

  @Test
  public void mutationsAppliedCorrectlyUnderConcurrency() {
    int n = 16;
    RespondWorkflowTaskCompletedRequest request = requestWithInputs(n);
    RespondWorkflowTaskCompletedRequest mutated =
        PayloadVisitors.visit(
            request,
            PayloadVisitorOptions.newBuilder(
                    (ctx, pls) -> {
                      Payload p = pls.get(0);
                      return Collections.singletonList(
                          p.toBuilder()
                              .setData(ByteString.copyFromUtf8(p.getData().toStringUtf8() + "!"))
                              .build());
                    })
                .setConcurrency(8)
                .setExecutor(executor)
                .build());
    for (int i = 0; i < n; i++) {
      assertEquals(
          "p" + i + "!",
          mutated
              .getCommands(i)
              .getScheduleActivityTaskCommandAttributes()
              .getInput()
              .getPayloads(0)
              .getData()
              .toStringUtf8());
    }
  }

  @Test
  public void concurrentVisitsRunOnProvidedExecutor() throws InterruptedException {
    AtomicInteger threadSeq = new AtomicInteger();
    ThreadFactory factory =
        r -> {
          Thread t = new Thread(r);
          t.setName("pv-exec-" + threadSeq.incrementAndGet());
          return t;
        };
    ExecutorService pool = Executors.newFixedThreadPool(4, factory);
    try {
      RespondWorkflowTaskCompletedRequest request = requestWithInputs(12);
      Set<String> visitThreads = ConcurrentHashMap.newKeySet();

      PayloadVisitors.visit(
          request,
          PayloadVisitorOptions.newBuilder(
                  (ctx, pls) -> {
                    visitThreads.add(Thread.currentThread().getName());
                    return pls;
                  })
              .setConcurrency(4)
              .setExecutor(pool)
              .build());

      assertTrue("no visits recorded", !visitThreads.isEmpty());
      for (String name : visitThreads) {
        assertTrue("visit ran off the provided executor: " + name, name.startsWith("pv-exec-"));
      }
    } finally {
      pool.shutdownNow();
      pool.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  public void sequentialConcurrencyIgnoresExecutorAndRunsOnCallingThread() {
    // With concurrency == 1 the executor must never be touched and visits run inline.
    AtomicInteger submittedToExecutor = new AtomicInteger();
    Executor tripwire =
        command -> {
          submittedToExecutor.incrementAndGet();
          command.run();
        };

    RespondWorkflowTaskCompletedRequest request = requestWithInputs(5);
    String callingThread = Thread.currentThread().getName();
    AtomicReference<String> sawDifferentThread = new AtomicReference<>();

    PayloadVisitors.visit(
        request,
        PayloadVisitorOptions.newBuilder(
                (ctx, pls) -> {
                  if (!Thread.currentThread().getName().equals(callingThread)) {
                    sawDifferentThread.set(Thread.currentThread().getName());
                  }
                  return pls;
                })
            .setConcurrency(1)
            .setExecutor(tripwire)
            .build());

    assertEquals("executor was used for sequential traversal", 0, submittedToExecutor.get());
    assertEquals("visit ran off the calling thread", null, sawDifferentThread.get());
  }
}
