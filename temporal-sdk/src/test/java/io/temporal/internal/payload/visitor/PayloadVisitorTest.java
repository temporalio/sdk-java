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
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
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

  /** A synchronous visit; {@link #toAsync} adapts it to the asynchronous {@link PayloadVisitor}. */
  @FunctionalInterface
  interface SyncPayloadVisitor {
    List<Payload> visit(Object ctx, List<Payload> payloads);
  }

  static PayloadVisitor<Object> toAsync(SyncPayloadVisitor visitor) {
    return (ctx, pls) -> CompletableFuture.completedFuture(visitor.visit(ctx, pls));
  }

  /**
   * Records every payload seen (in order) and the number of visit calls, leaving payloads
   * unchanged.
   */
  static final class CollectingVisitor implements SyncPayloadVisitor {
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

  static PayloadVisitorOptions<Object> options(SyncPayloadVisitor visitor) {
    return PayloadVisitorOptions.newBuilder(toAsync(visitor)).build();
  }

  /**
   * Blocks and unwraps the {@link CompletionException} {@code join} adds, exposing the original.
   */
  static <T extends com.google.protobuf.Message> T visit(
      T message, PayloadVisitorOptions<?> options) {
    return join(PayloadVisitors.visit(message, options));
  }

  static void visit(com.google.protobuf.Message.Builder builder, PayloadVisitorOptions<?> options) {
    join(PayloadVisitors.visit(builder, options));
  }

  private static <V> V join(CompletableFuture<V> future) {
    try {
      return future.join();
    } catch (CompletionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      }
      if (cause instanceof Error) {
        throw (Error) cause;
      }
      throw e;
    }
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

  /** Backs the simulated async visitors in the concurrency tests; the engine needs no executor. */
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
    RespondWorkflowTaskCompletedRequest unchanged = visit(request, options(counter));
    assertEquals(java.util.Arrays.asList("one", "two", "three"), counter.seen);
    // Two Payloads sequences (one per command's input): two visits, three payloads.
    assertEquals(2, counter.visits.get());
    assertEquals(request, unchanged);

    // Mutating: uppercase every payload's data.
    RespondWorkflowTaskCompletedRequest mutated =
        visit(
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
    Command result = visit(command, options(counter));
    assertEquals(Collections.singletonList("nexus"), counter.seen);

    // A single-payload field can be replaced with one payload.
    Command observed =
        visit(command, options((ctx, pls) -> Collections.singletonList(p("replaced"))));
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
        () -> visit(command, options((ctx, pls) -> Collections.emptyList())));

    // Returning more than one payload for a single-payload field is rejected.
    assertThrows(
        IllegalStateException.class,
        () -> visit(command, options((ctx, pls) -> java.util.Arrays.asList(p("a"), p("b")))));
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
    visit(command, options(counter));
    // A map<string, Payload> is visited once per entry; map iteration order is unspecified, so
    // assert the exact visit count and the value set rather than positional offsets.
    assertEquals(2, counter.visits.get());
    assertEquals(new HashSet<>(java.util.Arrays.asList("v1", "v2")), new HashSet<>(counter.seen));

    Command mutated =
        visit(command, options((ctx, pls) -> Collections.singletonList(p(data(pls.get(0)) + "!"))));
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
    visit(command, options(counter));
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
    visit(request, options(counter));
    assertEquals(Collections.singletonList("a"), counter.seen);

    RespondWorkflowTaskCompletedRequest mutated =
        visit(request, options((ctx, pls) -> Collections.singletonList(p(data(pls.get(0)) + "!"))));
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
    visit(response, options(counter));
    // A repeated Payload is one sequence: one visit, two payloads.
    assertEquals(1, counter.visits.get());
    assertEquals(java.util.Arrays.asList("g1", "g2"), counter.seen);

    CountWorkflowExecutionsResponse mutated =
        visit(
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
    Payloads unchanged = visit(root, options(counter));
    // The repeated Payload inside Payloads is one sequence: one visit, two payloads.
    assertEquals(1, counter.visits.get());
    assertEquals(java.util.Arrays.asList("a", "b"), counter.seen);
    assertEquals(root, unchanged);

    Payloads mutated = visit(root, options((ctx, pls) -> Collections.singletonList(p("x"))));
    assertEquals(payloads("x"), mutated);
  }

  @Test
  public void visitsBuilderInPlace() {
    RespondWorkflowTaskCompletedRequest.Builder builder =
        RespondWorkflowTaskCompletedRequest.newBuilder().addCommands(activity("a", payloads("x")));

    visit(builder, options((ctx, pls) -> Collections.singletonList(p("y"))));

    assertEquals(
        payloads("y"),
        builder.getCommands(0).getScheduleActivityTaskCommandAttributes().getInput());
  }

  @Test
  public void visitCountDistinguishesSequencesFromMapEntries() {
    // A Memo with two fields is visited once per entry: two visits, two payloads.
    Memo memo = Memo.newBuilder().putFields("a", p("1")).putFields("b", p("2")).build();
    CollectingVisitor memoVisitor = new CollectingVisitor();
    visit(memo, options(memoVisitor));
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
    visit(command, options(inputVisitor));
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
    visit(command, options(counter));
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
    visit(command, options(counter));
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
    visit(command, PayloadVisitorOptions.newBuilder(toAsync(counter)).setSkipHeaders(true).build());
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
    visit(
        command,
        PayloadVisitorOptions.newBuilder(toAsync(counter)).setSkipSearchAttributes(true).build());
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
                  return CompletableFuture.completedFuture(pls);
                })
            .setMessageVisitor(
                (current, msg) ->
                    msg instanceof Command.Builder
                        ? ((Command.Builder) msg).getCommandType()
                        : current)
            .build();

    visit(request, opts);
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
                  return CompletableFuture.completedFuture(pls);
                })
            .setInitialContext("root")
            .build();
    visit(command, opts);
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
                  return CompletableFuture.completedFuture(pls); // read-only
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
    visit(ok, validator);

    // Oversized blob trips the payload seam.
    RespondWorkflowTaskCompletedRequest bigBlob =
        RespondWorkflowTaskCompletedRequest.newBuilder()
            .addCommands(activity("a", payloads("way-too-large-payload")))
            .build();
    TestVisitorException blobError =
        assertThrows(TestVisitorException.class, () -> visit(bigBlob, validator));
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
        assertThrows(TestVisitorException.class, () -> visit(bigMemo, validator));
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
    visit(failure, options(counter));
    assertEquals(2, counter.seen.size());
    assertTrue(counter.seen.contains("d1"));
    assertTrue(counter.seen.contains("d2"));
  }

  @Test
  public void roundTripsPayloadInsideAny() throws Exception {
    Memo memo = Memo.newBuilder().putFields("k", p("inside-any")).build();
    Message message = Message.newBuilder().setBody(Any.pack(memo)).build();

    CollectingVisitor counter = new CollectingVisitor();
    Message result = visit(message, options(counter));
    assertEquals(Collections.singletonList("inside-any"), counter.seen);

    // Mutating through the Any re-packs correctly.
    Message mutated =
        visit(message, options((ctx, pls) -> Collections.singletonList(p("changed"))));
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
    Message result = visit(message, options(counter));
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
    Command result = visit(command, options(counter));
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
                visit(
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
                visit(
                    command,
                    PayloadVisitorOptions.newBuilder(toAsync((ctx, pls) -> pls))
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
    assertThrows(IllegalStateException.class, () -> visit(command, options((ctx, pls) -> null)));
  }

  // --- Concurrency ---
  //
  // These tests simulate an I/O-backed visitor with futures completed on a test-local thread pool,
  // so concurrency produces real overlap.

  @Test
  public void rejectsConcurrencyBelowOne() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PayloadVisitorOptions.newBuilder(toAsync((ctx, pls) -> pls)).setConcurrency(0).build());
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
  public void concurrencyEqualToWorkAllowsFullOverlap() {
    int n = 4;
    RespondWorkflowTaskCompletedRequest request = requestWithInputs(n);
    CyclicBarrier barrier = new CyclicBarrier(n);

    // All n visits must reach the barrier at once; with fewer than n in flight it would time out.
    visit(
        request,
        PayloadVisitorOptions.<Object>newBuilder(
                (ctx, pls) ->
                    CompletableFuture.supplyAsync(
                        () -> {
                          try {
                            barrier.await(5, TimeUnit.SECONDS);
                          } catch (InterruptedException
                              | BrokenBarrierException
                              | TimeoutException e) {
                            throw new RuntimeException(e);
                          }
                          return pls;
                        },
                        executor))
            .setConcurrency(n)
            .build());
  }

  @Test
  public void boundedConcurrencyNeverExceedsLimit() {
    int n = 8;
    int limit = 3;
    RespondWorkflowTaskCompletedRequest request = requestWithInputs(n);

    AtomicInteger inFlight = new AtomicInteger();
    AtomicInteger maxInFlight = new AtomicInteger();

    visit(
        request,
        PayloadVisitorOptions.<Object>newBuilder(
                (ctx, pls) ->
                    CompletableFuture.supplyAsync(
                        () -> {
                          int now = inFlight.incrementAndGet();
                          maxInFlight.accumulateAndGet(now, Math::max);
                          try {
                            Thread.sleep(20);
                          } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                          }
                          inFlight.decrementAndGet();
                          return pls;
                        },
                        executor))
            .setConcurrency(limit)
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
    List<String> order = Collections.synchronizedList(new ArrayList<>());

    // Concurrency 1 awaits each visit before the next, so even async visits run one at a time.
    visit(
        request,
        PayloadVisitorOptions.<Object>newBuilder(
                (ctx, pls) ->
                    CompletableFuture.supplyAsync(
                        () -> {
                          int now = inFlight.incrementAndGet();
                          maxInFlight.accumulateAndGet(now, Math::max);
                          order.add(pls.get(0).getData().toStringUtf8());
                          inFlight.decrementAndGet();
                          return pls;
                        },
                        executor))
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
    // The failure arrives as an exceptionally-completed future; it must surface unchanged.
    TestVisitorException thrown =
        assertThrows(
            TestVisitorException.class,
            () ->
                visit(
                    request,
                    PayloadVisitorOptions.<Object>newBuilder(
                            (ctx, pls) ->
                                CompletableFuture.supplyAsync(
                                    () -> {
                                      if (pls.get(0).getData().toStringUtf8().equals("p5")) {
                                        throw boom;
                                      }
                                      return pls;
                                    },
                                    executor))
                        .setConcurrency(4)
                        .build()));
    assertSame(boom, thrown);
  }

  @Test
  public void mutationsAppliedCorrectlyUnderConcurrency() {
    int n = 16;
    RespondWorkflowTaskCompletedRequest request = requestWithInputs(n);
    // Visits complete out of order, but write-backs apply in walk order, so each input is correct.
    RespondWorkflowTaskCompletedRequest mutated =
        visit(
            request,
            PayloadVisitorOptions.<Object>newBuilder(
                    (ctx, pls) ->
                        CompletableFuture.supplyAsync(
                            () -> {
                              Payload p = pls.get(0);
                              return Collections.singletonList(
                                  p.toBuilder()
                                      .setData(
                                          ByteString.copyFromUtf8(p.getData().toStringUtf8() + "!"))
                                      .build());
                            },
                            executor))
                .setConcurrency(8)
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
  public void entryPointReturnsPendingFutureWhileVisitInFlight() throws Exception {
    RespondWorkflowTaskCompletedRequest request = requestWithInputs(1);
    // A visit future we complete by hand, to observe the traversal future's state meanwhile.
    CompletableFuture<List<Payload>> gate = new CompletableFuture<>();

    CompletableFuture<RespondWorkflowTaskCompletedRequest> result =
        PayloadVisitors.visit(
            request, PayloadVisitorOptions.<Object>newBuilder((ctx, pls) -> gate).build());

    // The caller is not blocked: the traversal future is pending while the visit is outstanding.
    assertFalse(result.isDone());

    gate.complete(Collections.singletonList(p("done")));
    RespondWorkflowTaskCompletedRequest mutated = result.get(5, TimeUnit.SECONDS);
    assertEquals(
        "done",
        mutated
            .getCommands(0)
            .getScheduleActivityTaskCommandAttributes()
            .getInput()
            .getPayloads(0)
            .getData()
            .toStringUtf8());
  }
}
