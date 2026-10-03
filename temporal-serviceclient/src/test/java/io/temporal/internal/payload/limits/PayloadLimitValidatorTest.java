package io.temporal.internal.payload.limits;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.CompleteWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.ContinueAsNewWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.FailWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.ModifyWorkflowPropertiesCommandAttributes;
import io.temporal.api.command.v1.RecordMarkerCommandAttributes;
import io.temporal.api.command.v1.ScheduleActivityTaskCommandAttributes;
import io.temporal.api.command.v1.ScheduleNexusOperationCommandAttributes;
import io.temporal.api.common.v1.Header;
import io.temporal.api.common.v1.Memo;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.protocol.v1.Message;
import io.temporal.api.query.v1.WorkflowQueryResult;
import io.temporal.api.sdk.v1.UserMetadata;
import io.temporal.api.update.v1.Input;
import io.temporal.api.update.v1.Request;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.RespondActivityTaskFailedRequest;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.UpdateWorkflowExecutionRequest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;

public class PayloadLimitValidatorTest {

  // --- helpers ---------------------------------------------------------------

  private static Payload payload(int dataLen) {
    return Payload.newBuilder().setData(ByteString.copyFrom(new byte[dataLen])).build();
  }

  private static Payloads payloads(int dataLen) {
    return Payloads.newBuilder().addPayloads(payload(dataLen)).build();
  }

  private static Memo memoWith(String key, int dataLen) {
    return Memo.newBuilder().putFields(key, payload(dataLen)).build();
  }

  /** blobWarn = memoWarn = 10, with the given error thresholds. */
  private static PayloadLimits workerLimits(long blobError, long memoError) {
    return new PayloadLimits(10, blobError, 10, memoError);
  }

  private static RespondWorkflowTaskCompletedRequest wftWithCommand(Command command) {
    return RespondWorkflowTaskCompletedRequest.newBuilder().addCommands(command).build();
  }

  // --- end-to-end validation via PayloadLimitValidator -----------------------

  @Test
  public void blobFieldOverErrorLimitIsReported() {
    StartWorkflowExecutionRequest req =
        StartWorkflowExecutionRequest.newBuilder().setInput(payloads(1000)).build();
    Optional<PayloadLimitViolation> v = PayloadLimitValidator.validate(req, workerLimits(100, 100));
    assertTrue(v.isPresent());
    assertEquals(LimitClass.BLOB, v.get().getLimitClass());
    assertEquals(LimitSeverity.ERROR, v.get().getSeverity());
    assertEquals("input", v.get().getPath());
    assertTrue(v.get().getSize() > 100);
  }

  @Test
  public void memoFieldUsesMemoLimit() {
    // A memo over the memo error limit but well under the (huge) blob error limit must still error,
    // proving the memo class routes to the memo threshold.
    StartWorkflowExecutionRequest req =
        StartWorkflowExecutionRequest.newBuilder().setMemo(memoWith("k", 50)).build();
    PayloadLimits limits = new PayloadLimits(10, 1_000_000, 10, 20);
    Optional<PayloadLimitViolation> v = PayloadLimitValidator.validate(req, limits);
    assertTrue(v.isPresent());
    assertEquals(LimitClass.MEMO, v.get().getLimitClass());
    assertEquals("memo", v.get().getPath());
  }

  @Test
  public void warnOnlyClassifiedFieldNeverErrors() {
    // RespondActivityTaskFailed.failure is classified warn-only, so even with an error limit a huge
    // failure produces no error-level violation.
    RespondActivityTaskFailedRequest req =
        RespondActivityTaskFailedRequest.newBuilder()
            .setFailure(Failure.newBuilder().setMessage(repeat("x", 10_000)))
            .build();
    assertFalse(PayloadLimitValidator.validate(req, workerLimits(100, 100)).isPresent());
  }

  @Test
  public void underLimitIsOk() {
    StartWorkflowExecutionRequest req =
        StartWorkflowExecutionRequest.newBuilder().setInput(payloads(5)).build();
    assertFalse(PayloadLimitValidator.validate(req, workerLimits(100_000, 100_000)).isPresent());
  }

  @Test
  public void requestWithoutPayloadFieldsIsIgnored() {
    assertFalse(
        PayloadLimitValidator.validate(
                DescribeWorkflowExecutionRequest.getDefaultInstance(), workerLimits(1, 1))
            .isPresent());
  }

  @Test
  public void blobClassedMemoIsMeasuredAsFieldsDataSum() {
    // ModifyWorkflowProperties.upserted_memo is blob-classed, so it is measured as the data-sum of
    // its fields (key bytes + payload data bytes), NOT the whole-Memo proto size.
    Memo memo =
        Memo.newBuilder().putFields("ab", payload(10)).putFields("cde", payload(20)).build();
    RespondWorkflowTaskCompletedRequest req =
        wftWithCommand(
            Command.newBuilder()
                .setModifyWorkflowPropertiesCommandAttributes(
                    ModifyWorkflowPropertiesCommandAttributes.newBuilder().setUpsertedMemo(memo))
                .build());
    // data-sum = (2 + 10) + (3 + 20) = 35; blobError = 30 -> error, classified BLOB.
    Optional<PayloadLimitViolation> v =
        PayloadLimitValidator.validate(req, workerLimits(30, 1_000_000));
    assertTrue(v.isPresent());
    assertEquals(LimitClass.BLOB, v.get().getLimitClass());
    assertEquals(
        "commands[0].modify_workflow_properties_command_attributes.upserted_memo",
        v.get().getPath());
    assertEquals(35, v.get().getSize());
  }

  @Test
  public void markerDetailsMapIsMeasuredAsPayloadsSum() {
    RespondWorkflowTaskCompletedRequest req =
        wftWithCommand(
            Command.newBuilder()
                .setRecordMarkerCommandAttributes(
                    RecordMarkerCommandAttributes.newBuilder().putDetails("marker", payloads(1000)))
                .build());
    Optional<PayloadLimitViolation> v = PayloadLimitValidator.validate(req, workerLimits(100, 100));
    assertTrue(v.isPresent());
    assertEquals(LimitClass.BLOB, v.get().getLimitClass());
    assertEquals("commands[0].record_marker_command_attributes.details", v.get().getPath());
  }

  @Test
  public void singlePayloadFieldIsMeasuredAsPayloadSize() {
    // ScheduleNexusOperation.input is a single Payload (not Payloads).
    RespondWorkflowTaskCompletedRequest req =
        wftWithCommand(
            Command.newBuilder()
                .setScheduleNexusOperationCommandAttributes(
                    ScheduleNexusOperationCommandAttributes.newBuilder().setInput(payload(1000)))
                .build());
    Optional<PayloadLimitViolation> v = PayloadLimitValidator.validate(req, workerLimits(100, 100));
    assertTrue(v.isPresent());
    assertEquals(LimitClass.BLOB, v.get().getLimitClass());
    assertEquals(
        "commands[0].schedule_nexus_operation_command_attributes.input", v.get().getPath());
  }

  @Test
  public void wholeFailureIsMeasuredAsMessageSize() {
    RespondWorkflowTaskCompletedRequest req =
        wftWithCommand(
            Command.newBuilder()
                .setFailWorkflowExecutionCommandAttributes(
                    FailWorkflowExecutionCommandAttributes.newBuilder()
                        .setFailure(Failure.newBuilder().setMessage(repeat("x", 1000))))
                .build());
    Optional<PayloadLimitViolation> v = PayloadLimitValidator.validate(req, workerLimits(100, 100));
    assertTrue(v.isPresent());
    assertEquals(LimitClass.BLOB, v.get().getLimitClass());
    assertEquals(
        "commands[0].fail_workflow_execution_command_attributes.failure", v.get().getPath());
  }

  @Test
  public void tmprl1103MessageText() {
    StartWorkflowExecutionRequest req =
        StartWorkflowExecutionRequest.newBuilder().setInput(payloads(1000)).build();
    PayloadLimitViolation v = PayloadLimitValidator.validate(req, workerLimits(100, 100)).get();
    assertEquals(
        "[TMPRL1103] Attempted to upload payloads with size that exceeded the error limit.",
        v.getMessage());
  }

  // --- CollectingSink: classification, independent of logging/early-return ----

  @Test
  public void collectingSinkClassifiesErrorVsWarning() {
    CollectingSink sink = new CollectingSink(workerLimits(100, 100)); // blobWarn=10, blobError=100
    sink.check("over_error", LimitClass.BLOB, 200, true);
    sink.check("over_warn", LimitClass.BLOB, 50, true);
    sink.check("under_warn", LimitClass.BLOB, 5, true);
    assertEquals(1, sink.getErrors().size());
    assertEquals("over_error", sink.getErrors().get(0).getPath());
    assertEquals(100, sink.getErrors().get(0).getLimit());
    assertEquals(1, sink.getWarnings().size());
    assertEquals("over_warn", sink.getWarnings().get(0).getPath());
    assertEquals(10, sink.getWarnings().get(0).getLimit());
  }

  @Test
  public void collectingSinkWarnOnlyFieldNeverErrors() {
    CollectingSink sink = new CollectingSink(workerLimits(100, 100));
    sink.check("warn_only", LimitClass.BLOB, 5000, false); // enforceError = false
    assertTrue(sink.getErrors().isEmpty());
    assertEquals(1, sink.getWarnings().size());
  }

  @Test
  public void collectingSinkNoErrorLimitOnlyWarns() {
    CollectingSink sink = new CollectingSink(new PayloadLimits(100, 0, 0, 0));
    sink.check("big", LimitClass.BLOB, 101, true); // error threshold 0 disables errors
    assertTrue(sink.getErrors().isEmpty());
    assertEquals(1, sink.getWarnings().size());
  }

  @Test
  public void collectingSinkZeroWarnDisablesWarnings() {
    CollectingSink sink = new CollectingSink(PayloadLimits.none());
    sink.check("big", LimitClass.BLOB, 5000, true);
    assertTrue(sink.getErrors().isEmpty());
    assertTrue(sink.getWarnings().isEmpty());
  }

  @Test
  public void collectingSinkRoutesMemoToMemoLimit() {
    CollectingSink sink = new CollectingSink(workerLimits(1_000_000, 20));
    sink.check("blob_field", LimitClass.BLOB, 100, true); // fine: huge blob limit
    sink.check("memo_field", LimitClass.MEMO, 100, true); // errors: tiny memo limit
    assertEquals(1, sink.getErrors().size());
    assertEquals(LimitClass.MEMO, sink.getErrors().get(0).getLimitClass());
    assertEquals("memo_field", sink.getErrors().get(0).getPath());
  }

  // --- Which fields get visited (order-independent) --------------------------

  @Test
  public void visitsPayloadFieldsOfEachCommandWithPaths() {
    RespondWorkflowTaskCompletedRequest req =
        RespondWorkflowTaskCompletedRequest.newBuilder()
            .addCommands(
                Command.newBuilder()
                    .setScheduleActivityTaskCommandAttributes(
                        ScheduleActivityTaskCommandAttributes.newBuilder().setInput(payloads(1))))
            .addCommands(
                Command.newBuilder()
                    .setCompleteWorkflowExecutionCommandAttributes(
                        CompleteWorkflowExecutionCommandAttributes.newBuilder()
                            .setResult(payloads(1))))
            .build();
    RecordingSink sink = new RecordingSink();
    GeneratedPayloadLimitValidator.dispatch(sink, req);
    assertEquals(
        Arrays.asList(
            "commands[0].schedule_activity_task_command_attributes.input",
            "commands[1].complete_workflow_execution_command_attributes.result"),
        sink.sorted());
  }

  @Test
  public void visitsOnlyPresentFields() {
    // Only input is set; memo/search_attributes/etc. are absent and must not be visited.
    StartWorkflowExecutionRequest req =
        StartWorkflowExecutionRequest.newBuilder().setInput(payloads(1)).build();
    RecordingSink sink = new RecordingSink();
    GeneratedPayloadLimitValidator.dispatch(sink, req);
    assertEquals(Collections.singletonList("input"), sink.sorted());
  }

  @Test
  public void visitsProtocolMessageBody() {
    // Message.body is a google.protobuf.Any reached because Message is a forced whole-message leaf
    // and the parent recurses into `messages`.
    RespondWorkflowTaskCompletedRequest req =
        RespondWorkflowTaskCompletedRequest.newBuilder()
            .addMessages(Message.newBuilder().setBody(Any.getDefaultInstance()))
            .build();
    RecordingSink sink = new RecordingSink();
    GeneratedPayloadLimitValidator.dispatch(sink, req);
    assertEquals(Collections.singletonList("messages[0].body"), sink.sorted());
  }

  // --- NOT_VALIDATED fields must never be checked ---------------------------
  //
  // These are the tests that catch an accidental reclassification: a field the server does not
  // size-check (or checks in a way the SDK cannot replicate) must produce no callback at all, so
  // asserting the exact visited set is stronger than asserting no violation.

  @Test
  public void startWorkflowVisitsOnlyItsValidatedFields() {
    StartWorkflowExecutionRequest req =
        StartWorkflowExecutionRequest.newBuilder()
            .setInput(payloads(1)) // blob
            .setMemo(memoWith("k", 1)) // memo
            .setHeader(Header.newBuilder().putFields("h", payload(1))) // metric only
            .setSearchAttributes(
                SearchAttributes.newBuilder().putIndexedFields("sa", payload(1))) // server-state
            .setLastCompletionResult(payloads(1)) // carry-over, not checked here
            .setContinuedFailure(Failure.newBuilder().setMessage("boom")) // carry-over
            .setUserMetadata(
                UserMetadata.newBuilder()
                    .setSummary(payload(1))
                    .setDetails(payload(1))) // dedicated non-fetchable limits
            .build();
    RecordingSink sink = new RecordingSink();
    GeneratedPayloadLimitValidator.dispatch(sink, req);
    assertEquals(Arrays.asList("input", "memo"), sink.sorted());
  }

  @Test
  public void continueAsNewVisitsOnlyItsValidatedFields() {
    RespondWorkflowTaskCompletedRequest req =
        wftWithCommand(
            Command.newBuilder()
                .setContinueAsNewWorkflowExecutionCommandAttributes(
                    ContinueAsNewWorkflowExecutionCommandAttributes.newBuilder()
                        .setInput(payloads(1)) // blob
                        .setMemo(memoWith("k", 1)) // memo
                        .setHeader(Header.newBuilder().putFields("h", payload(1)))
                        .setSearchAttributes(
                            SearchAttributes.newBuilder().putIndexedFields("sa", payload(1)))
                        .setFailure(Failure.newBuilder().setMessage("boom"))
                        .setLastCompletionResult(payloads(1)))
                .build());
    RecordingSink sink = new RecordingSink();
    GeneratedPayloadLimitValidator.dispatch(sink, req);
    assertEquals(
        Arrays.asList(
            "commands[0].continue_as_new_workflow_execution_command_attributes.input",
            "commands[0].continue_as_new_workflow_execution_command_attributes.memo"),
        sink.sorted());
  }

  @Test
  public void recordMarkerVisitsOnlyDetails() {
    RespondWorkflowTaskCompletedRequest req =
        wftWithCommand(
            Command.newBuilder()
                .setRecordMarkerCommandAttributes(
                    RecordMarkerCommandAttributes.newBuilder()
                        .putDetails("marker", payloads(1)) // blob
                        .setHeader(Header.newBuilder().putFields("h", payload(1)))
                        .setFailure(Failure.newBuilder().setMessage("boom")))
                .build());
    RecordingSink sink = new RecordingSink();
    GeneratedPayloadLimitValidator.dispatch(sink, req);
    assertEquals(
        Collections.singletonList("commands[0].record_marker_command_attributes.details"),
        sink.sorted());
  }

  @Test
  public void requestsWithOnlyNotValidatedFieldsProduceNoChecks() {
    RecordingSink terminate = new RecordingSink();
    GeneratedPayloadLimitValidator.dispatch(
        terminate,
        TerminateWorkflowExecutionRequest.newBuilder().setDetails(payloads(5000)).build());
    assertEquals(Collections.emptyList(), terminate.sorted());

    // Update args are recorded as a metric by the frontend; the size the server enforces is the
    // protocol Message body on delivery, which is checked on the worker's completion instead.
    RecordingSink update = new RecordingSink();
    GeneratedPayloadLimitValidator.dispatch(
        update,
        UpdateWorkflowExecutionRequest.newBuilder()
            .setRequest(
                Request.newBuilder()
                    .setInput(
                        Input.newBuilder()
                            .setArgs(payloads(5000))
                            .setHeader(Header.newBuilder().putFields("h", payload(1)))))
            .build());
    assertEquals(Collections.emptyList(), update.sorted());
  }

  @Test
  public void mapKeyedFieldsRenderTheKeyInThePath() {
    RespondWorkflowTaskCompletedRequest req =
        RespondWorkflowTaskCompletedRequest.newBuilder()
            .putQueryResults(
                "query-id", WorkflowQueryResult.newBuilder().setAnswer(payloads(1)).build())
            .build();
    RecordingSink sink = new RecordingSink();
    GeneratedPayloadLimitValidator.dispatch(sink, req);
    assertEquals(Collections.singletonList("query_results[query-id].answer"), sink.sorted());
  }

  // --- size helpers ---------------------------------------------------------

  @Test
  public void mapPayloadDataSumCountsUtf8KeyBytesAndRawData() {
    // The server sums len(key) + len(payload.data) over Go strings, i.e. UTF-8 bytes: the 2-char
    // key below is 6 bytes, so a char count would under-measure it.
    Map<String, Payload> fields = new HashMap<>();
    fields.put("\u00e9\u4e2d", payload(10)); // 2 + 3 UTF-8 bytes
    assertEquals(15, PayloadLimitSizes.mapPayloadDataSum(fields));
  }

  private static String repeat(String s, int n) {
    StringBuilder sb = new StringBuilder(s.length() * n);
    for (int i = 0; i < n; i++) {
      sb.append(s);
    }
    return sb.toString();
  }

  /** A sink that records the path of each visited field (order is not significant). */
  private static final class RecordingSink implements PayloadLimitSink {
    private final PayloadPath path = new PayloadPath();
    private final List<String> visited = new ArrayList<>();

    @Override
    public void check(String fieldName, LimitClass limitClass, long size, boolean enforceError) {
      visited.add(path.leaf(fieldName));
    }

    @Override
    public void enter(String name) {
      path.push(name);
    }

    @Override
    public void enter(String name, int index) {
      path.push(name, index);
    }

    @Override
    public void enter(String name, String key) {
      path.push(name, key);
    }

    @Override
    public void exit() {
      path.pop();
    }

    List<String> sorted() {
      List<String> v = new ArrayList<>(visited);
      Collections.sort(v);
      return v;
    }
  }
}
