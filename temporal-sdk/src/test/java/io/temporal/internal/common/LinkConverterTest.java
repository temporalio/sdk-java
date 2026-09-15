package io.temporal.internal.common;

import static io.temporal.internal.common.LinkConverter.linkToNexusLink;
import static io.temporal.internal.common.LinkConverter.nexusLinkToActivity;
import static io.temporal.internal.common.LinkConverter.nexusLinkToLink;
import static io.temporal.internal.common.LinkConverter.nexusLinkToNexusOperation;
import static io.temporal.internal.common.LinkConverter.nexusLinkToWorkflowEvent;
import static io.temporal.internal.common.LinkConverter.nexusLinkToWorkflowLink;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import io.temporal.api.common.v1.Link;
import io.temporal.api.enums.v1.EventType;
import org.junit.Test;

/**
 * Tests for {@link LinkConverter}.
 *
 * <p>The URL shapes and the encoding rules asserted here are a wire contract shared with the Go,
 * Python, TypeScript and .NET SDKs, so changing an expected URL means changing it everywhere.
 *
 * <p>Three things are deliberately not pinned as contract, because no decoder can observe them:
 * query parameter order, whether a space in a query value is {@code +} or {@code %20}, and whether
 * a literal {@code +} in a path is bare or {@code %2B}. Tests that assert Java's concrete choice
 * for those say so.
 */
public class LinkConverterTest {

  private static final String WORKFLOW_EVENT = Link.WorkflowEvent.getDescriptor().getFullName();
  private static final String WORKFLOW = Link.Workflow.getDescriptor().getFullName();
  private static final String NEXUS_OPERATION = Link.NexusOperation.getDescriptor().getFullName();
  private static final String ACTIVITY = Link.Activity.getDescriptor().getFullName();

  // ===============================================================================================
  // Encode.
  // ===============================================================================================

  @Test
  public void encodesWorkflowEventWithEventReference() {
    assertEncodes(
        "temporal:///namespaces/ns/workflows/wf-id/run-id/history"
            + "?referenceType=EventReference&eventID=1&eventType=WorkflowExecutionStarted",
        WORKFLOW_EVENT,
        eventRef("ns", "wf-id", "run-id", 1, EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED));
  }

  /** An unset event ID is 0, which is not a valid event ID, so the param is omitted. */
  @Test
  public void omitsEventIdWhenUnset() {
    assertEncodes(
        "temporal:///namespaces/ns/workflows/wf-id/run-id/history"
            + "?referenceType=EventReference&eventType=WorkflowExecutionStarted",
        WORKFLOW_EVENT,
        eventRef("ns", "wf-id", "run-id", 0, EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED));
  }

  @Test
  public void encodesWorkflowEventWithRequestIdReference() {
    assertEncodes(
        "temporal:///namespaces/ns/workflows/wf-id/run-id/history"
            + "?referenceType=RequestIdReference&requestID=req-id"
            + "&eventType=WorkflowExecutionOptionsUpdated",
        WORKFLOW_EVENT,
        requestIdRef(
            "ns",
            "wf-id",
            "run-id",
            "req-id",
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_OPTIONS_UPDATED));
  }

  /**
   * A space in a path segment must be {@code %20}, never {@code +}: the decoding server treats a
   * {@code +} in a path as a literal plus, so the space would be lost and the link would point at a
   * workflow that does not exist. Regression guard for #2874.
   */
  @Test
  public void encodesSpaceInPathAsPercent20() {
    assertEncodes(
        "temporal:///namespaces/ns/workflows/wf%20id/run-id/history"
            + "?referenceType=EventReference&eventID=1&eventType=WorkflowExecutionStarted",
        WORKFLOW_EVENT,
        eventRef("ns", "wf id", "run-id", 1, EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED));
  }

  /** An encoded slash must stay encoded, or the path gains a segment and no longer parses. */
  @Test
  public void encodesSlashAndAngleInPathSegment() {
    assertEncodes(
        "temporal:///namespaces/ns/workflows/wf-id%2F/run-id/history"
            + "?referenceType=EventReference&eventID=1&eventType=WorkflowExecutionStarted",
        WORKFLOW_EVENT,
        eventRef("ns", "wf-id/", "run-id", 1, EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED));
    assertEncodes(
        "temporal:///namespaces/ns/workflows/wf-id%3E/run-id/history"
            + "?referenceType=EventReference&eventID=1&eventType=WorkflowExecutionStarted",
        WORKFLOW_EVENT,
        eventRef("ns", "wf-id>", "run-id", 1, EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED));
  }

  @Test
  public void encodesNonAsciiPathSegment() {
    assertEncodes(
        "temporal:///namespaces/ns%C3%A4/workflows/wf-id/run-id/history"
            + "?referenceType=EventReference&eventID=7&eventType=NexusOperationScheduled",
        WORKFLOW_EVENT,
        eventRef("nsä", "wf-id", "run-id", 7, EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED));
  }

  /** A workflow link addresses the execution as a whole, so it has no {@code /history} tail. */
  @Test
  public void encodesWorkflowLink() {
    assertEncodes(
        "temporal:///namespaces/ns/workflows/wf-id/run-id",
        WORKFLOW,
        workflow("ns", "wf-id", "run-id", ""));
  }

  /** The space encoding in the query value ({@code +}) is Java's choice, not contract. */
  @Test
  public void encodesWorkflowLinkReason() {
    assertEncodes(
        "temporal:///namespaces/ns/workflows/wf-id/run-id?reason=rejected+update",
        WORKFLOW,
        workflow("ns", "wf-id", "run-id", "rejected update"));
  }

  @Test
  public void encodesWorkflowLinkPathSegments() {
    assertEncodes(
        "temporal:///namespaces/ns/workflows/wf%20id/run-id",
        WORKFLOW, workflow("ns", "wf id", "run-id", ""));
    assertEncodes(
        "temporal:///namespaces/ns/workflows/wf-id%2F/run-id",
        WORKFLOW, workflow("ns", "wf-id/", "run-id", ""));
  }

  /** A literal plus must survive; a bare {@code +} would form-decode back to a space. */
  @Test
  public void encodesLiteralPlusInReason() {
    assertEncodes(
        "temporal:///namespaces/ns/workflows/wf-id/run-id?reason=a%2Bb",
        WORKFLOW, workflow("ns", "wf-id", "run-id", "a+b"));
  }

  /** A reason may contain the query delimiters themselves; they must not split the parameter. */
  @Test
  public void encodesReasonContainingDelimiters() {
    Link in = workflow("ns", "wf-id", "run-id", "a&b=c");
    assertEquals(in, nexusLinkToLink(linkToNexusLink(in)));
  }

  @Test
  public void encodesNexusOperationLink() {
    assertEncodes(
        "temporal:///namespaces/ns/nexus-operations/op-id/run-id/details",
        NEXUS_OPERATION,
        nexusOperation("ns", "op-id", "run-id"));
    assertEncodes(
        "temporal:///namespaces/ns/nexus-operations/op%2Fid/run-id/details",
        NEXUS_OPERATION, nexusOperation("ns", "op/id", "run-id"));
  }

  @Test
  public void encodesActivityLink() {
    assertEncodes(
        "temporal:///namespaces/ns/activities/act-id/run-id/details",
        ACTIVITY,
        activity("ns", "act-id", "run-id"));
    assertEncodes(
        "temporal:///namespaces/ns/activities/act%2Fid/run-id/details",
        ACTIVITY, activity("ns", "act/id", "run-id"));
  }

  /** The event type goes on the wire in the short PascalCase form. */
  @Test
  public void encodesEventTypeInPascalCase() {
    assertEncodes(
        "temporal:///namespaces/ns/workflows/wf-id/run-id/history"
            + "?referenceType=EventReference&eventID=2&eventType=NexusOperationCancelRequested",
        WORKFLOW_EVENT,
        eventRef(
            "ns", "wf-id", "run-id", 2, EventType.EVENT_TYPE_NEXUS_OPERATION_CANCEL_REQUESTED));
  }

  /** Query parameter order is not contract, but Java's order is pinned so it stays deliberate. */
  @Test
  public void emitsQueryParametersInInsertionOrder() {
    assertEquals(
        "temporal:///namespaces/ns/workflows/wf-id/run-id/history"
            + "?referenceType=EventReference&eventID=1&eventType=WorkflowExecutionStarted",
        linkToNexusLink(
                eventRef(
                    "ns", "wf-id", "run-id", 1, EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED))
            .getUrl());
  }

  // ===============================================================================================
  // Decode.
  // ===============================================================================================

  /** Both event type spellings must decode; other SDKs emit the prefixed form. */
  @Test
  public void decodesEitherEventTypeSpelling() {
    Link expected =
        eventRef("ns", "wf-id", "run-id", 1, EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED);
    assertDecodes(
        expected,
        WORKFLOW_EVENT,
        "temporal:///namespaces/ns/workflows/wf-id/run-id/history"
            + "?referenceType=EventReference&eventID=1&eventType=WorkflowExecutionStarted");
    assertDecodes(
        expected,
        WORKFLOW_EVENT,
        "temporal:///namespaces/ns/workflows/wf-id/run-id/history"
            + "?referenceType=EventReference&eventID=1"
            + "&eventType=EVENT_TYPE_WORKFLOW_EXECUTION_STARTED");
  }

  /** Parameters are read by key, so any order decodes. */
  @Test
  public void decodesQueryParametersInAnyOrder() {
    assertDecodes(
        eventRef("ns", "wf-id", "run-id", 1, EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED),
        WORKFLOW_EVENT,
        "temporal:///namespaces/ns/workflows/wf-id/run-id/history"
            + "?eventType=WorkflowExecutionStarted&referenceType=EventReference&eventID=1");
    assertDecodes(
        workflow("ns", "wf-id", "run-id", "why"),
        WORKFLOW,
        "temporal:///namespaces/ns/workflows/wf-id/run-id?other=x&reason=why");
  }

  /**
   * Path segments are percent-decoded, not form-decoded, in both legal spellings of a plus. Other
   * SDKs emit the bare form; neither may ever decode to a space.
   */
  @Test
  public void decodesBothSpellingsOfPlusInPathAsPlus() {
    Link expected =
        eventRef("ns", "a+b", "run-id", 1, EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED);
    for (String segment : new String[] {"a+b", "a%2Bb"}) {
      assertDecodes(
          expected,
          WORKFLOW_EVENT,
          "temporal:///namespaces/ns/workflows/"
              + segment
              + "/run-id/history?referenceType=EventReference&eventID=1"
              + "&eventType=WorkflowExecutionStarted");
    }
  }

  @Test
  public void decodesPercentEncodedPathSegments() {
    assertDecodes(
        eventRef("ns", "wf id", "run-id", 1, EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED),
        WORKFLOW_EVENT,
        "temporal:///namespaces/ns/workflows/wf%20id/run-id/history"
            + "?referenceType=EventReference&eventID=1&eventType=WorkflowExecutionStarted");
    assertDecodes(
        activity("ns", "act id", "run-id"),
        ACTIVITY,
        "temporal:///namespaces/ns/activities/act%20id/run-id/details");
    assertDecodes(
        nexusOperation("ns", "op/id", "run-id"),
        NEXUS_OPERATION,
        "temporal:///namespaces/ns/nexus-operations/op%2Fid/run-id/details");
  }

  /**
   * Query values are form-decoded, the opposite of path segments, so both spellings of a space must
   * decode to a space. .NET emits the percent form.
   */
  @Test
  public void decodesBothSpellingsOfSpaceInQueryValue() {
    Link expected = workflow("ns", "wf-id", "run-id", "rejected update");
    for (String value : new String[] {"rejected+update", "rejected%20update"}) {
      assertDecodes(
          expected, WORKFLOW, "temporal:///namespaces/ns/workflows/wf-id/run-id?reason=" + value);
    }
  }

  /** Values are read from the raw query, so a percent sign does not discard the link. */
  @Test
  public void decodesPercentSignInQueryValue() {
    assertDecodes(
        requestIdRef(
            "ns", "wf-id", "run-id", "100%", EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED),
        WORKFLOW_EVENT,
        "temporal:///namespaces/ns/workflows/wf-id/run-id/history"
            + "?referenceType=RequestIdReference&requestID=100%25"
            + "&eventType=WorkflowExecutionStarted");
  }

  /** An absent, empty, bare or similarly-named reason parameter all leave the proto default. */
  @Test
  public void decodesWorkflowLinkWithoutUsableReason() {
    Link expected = workflow("ns", "wf-id", "run-id", "");
    String base = "temporal:///namespaces/ns/workflows/wf-id/run-id";
    assertDecodes(expected, WORKFLOW, base);
    assertDecodes(expected, WORKFLOW, base + "?reason=");
    assertDecodes(expected, WORKFLOW, base + "?reason");
    assertDecodes(expected, WORKFLOW, base + "?reasonable=yes");
  }

  // ===============================================================================================
  // Rejection.
  // ===============================================================================================

  @Test
  public void rejectsWrongScheme() {
    assertRejected(WORKFLOW_EVENT, "https:///namespaces/ns/workflows/wf-id/run-id/history");
    assertRejected(WORKFLOW, "https:///namespaces/ns/workflows/wf-id/run-id");
    assertRejected(NEXUS_OPERATION, "https:///namespaces/ns/nexus-operations/op/run-id/details");
  }

  /** A workflow link ends at the run ID; a workflow-event link ends at {@code /history}. */
  @Test
  public void rejectsMismatchedWorkflowPathShapes() {
    assertRejected(WORKFLOW, "temporal:///namespaces/ns/workflows/wf-id/run-id/history");
    assertRejected(WORKFLOW_EVENT, "temporal:///namespaces/ns/workflows/wf-id/run-id");
  }

  @Test
  public void rejectsTrailingPathSegment() {
    assertRejected(
        WORKFLOW_EVENT, "temporal:///namespaces/ns/workflows/wf-id/run-id/history/extra");
    assertRejected(WORKFLOW, "temporal:///namespaces/ns/workflows/wf-id/run-id/extra");
    // A trailing slash is an empty extra segment, not a no-op.
    assertRejected(WORKFLOW_EVENT, "temporal:///namespaces/ns/workflows/wf-id/run-id/history/");
    assertRejected(WORKFLOW, "temporal:///namespaces/ns/workflows/wf-id/run-id/");
  }

  /** A repeated key takes its first occurrence, as api-go does. No encoder emits one. */
  @Test
  public void decodesFirstOccurrenceOfARepeatedQueryKey() {
    assertDecodes(
        workflow("ns", "wf-id", "run-id", "first"),
        WORKFLOW,
        "temporal:///namespaces/ns/workflows/wf-id/run-id?reason=first&reason=second");
  }

  @Test
  public void rejectsMissingPathSegment() {
    assertRejected(WORKFLOW, "temporal:///namespaces/ns/workflows/wf-id");
    assertRejected(NEXUS_OPERATION, "temporal:///namespaces/ns/nexus-operations/op-id/run-id");
    assertRejected(ACTIVITY, "temporal:///namespaces/ns/activities/act-id/run-id");
  }

  @Test
  public void rejectsEmptyPathSegment() {
    assertRejected(WORKFLOW_EVENT, "temporal:///namespaces//workflows/wf-id/run-id/history");
    assertRejected(WORKFLOW_EVENT, "temporal:///namespaces/ns/workflows//run-id/history");
  }

  @Test
  public void rejectsWrongKindSegment() {
    assertRejected(WORKFLOW_EVENT, "temporal:///namespaces/ns/activities/wf-id/run-id/history");
  }

  /** The declared type is authoritative on every decoder, not just the dispatcher. */
  @Test
  public void rejectsTypeThatDoesNotMatchThePath() {
    String workflowEventUrl = "temporal:///namespaces/ns/workflows/wf-id/run-id/history";
    assertRejected(ACTIVITY, workflowEventUrl);
    assertNull(nexusLinkToWorkflowEvent(nexusLink(WORKFLOW, workflowEventUrl)));
    assertNull(nexusLinkToWorkflowLink(nexusLink(WORKFLOW_EVENT, workflowEventUrl)));
    assertNull(nexusLinkToActivity(nexusLink(WORKFLOW_EVENT, workflowEventUrl)));
    assertNull(nexusLinkToNexusOperation(nexusLink(WORKFLOW_EVENT, workflowEventUrl)));
  }

  @Test
  public void rejectsUnknownLinkType() {
    assertRejected(
        "temporal.api.common.v1.Link.NotAVariant",
        "temporal:///namespaces/ns/workflows/wf-id/run-id/history");
  }

  @Test
  public void rejectsMissingOrUnknownReferenceType() {
    assertRejected(
        WORKFLOW_EVENT,
        "temporal:///namespaces/ns/workflows/wf-id/run-id/history"
            + "?eventID=1&eventType=WorkflowExecutionStarted");
    assertRejected(
        WORKFLOW_EVENT,
        "temporal:///namespaces/ns/workflows/wf-id/run-id/history"
            + "?referenceType=NotAReference&eventType=WorkflowExecutionStarted");
  }

  @Test
  public void rejectsUnparseableEventTypeOrEventId() {
    assertRejected(
        WORKFLOW_EVENT,
        "temporal:///namespaces/ns/workflows/wf-id/run-id/history"
            + "?referenceType=EventReference&eventType=NotAnEventType");
    assertRejected(
        WORKFLOW_EVENT,
        "temporal:///namespaces/ns/workflows/wf-id/run-id/history"
            + "?referenceType=EventReference&eventID=nope&eventType=WorkflowExecutionStarted");
    assertRejected(
        WORKFLOW_EVENT,
        "temporal:///namespaces/ns/workflows/wf-id/run-id/history"
            + "?referenceType=EventReference&eventID=99999999999999999999"
            + "&eventType=WorkflowExecutionStarted");
  }

  /** A malformed link is dropped, never thrown, so it cannot fail the call carrying it. */
  @Test
  public void rejectsMalformedUrlsWithoutThrowing() {
    for (String url :
        new String[] {"", "not a uri at all", "%%%", "temporal:///", "temporal:///namespaces"}) {
      assertRejected(WORKFLOW_EVENT, url);
    }
  }

  // ===============================================================================================
  // Dispatch.
  // ===============================================================================================

  @Test
  public void dispatchCoversAllFourLinkTypes() {
    assertNotNull(linkToNexusLink(eventRef("ns", "w", "r", 1, EventType.EVENT_TYPE_TIMER_STARTED)));
    assertNotNull(linkToNexusLink(workflow("ns", "w", "r", "")));
    assertNotNull(linkToNexusLink(nexusOperation("ns", "o", "r")));
    assertNotNull(linkToNexusLink(activity("ns", "a", "r")));
  }

  /**
   * An event type this SDK's protos do not know (a newer server sending a higher enum number)
   * arrives as {@code UNRECOGNIZED}, which has no {@code EVENT_TYPE_} prefix to strip. Encoding it
   * must drop the link rather than throw out of the Nexus call carrying it.
   */
  @Test
  public void unknownEventTypeEncodesToNullRatherThanThrowing() {
    Link link =
        Link.newBuilder()
            .setWorkflowEvent(
                Link.WorkflowEvent.newBuilder()
                    .setNamespace("ns")
                    .setWorkflowId("wf-id")
                    .setRunId("run-id")
                    .setEventRef(
                        Link.WorkflowEvent.EventReference.newBuilder()
                            .setEventId(1)
                            .setEventTypeValue(99999)))
            .build();
    try {
      assertNull(linkToNexusLink(link));
    } catch (RuntimeException e) {
      fail("must not throw: " + e);
    }
  }

  @Test
  public void unsetVariantEncodesToNull() {
    assertNull(linkToNexusLink(Link.newBuilder().build()));
  }

  /** A batch-job link is a real proto variant that no SDK converts. */
  @Test
  public void batchJobVariantEncodesToNull() {
    assertNull(
        linkToNexusLink(
            Link.newBuilder().setBatchJob(Link.BatchJob.newBuilder().setJobId("job")).build()));
  }

  // ===============================================================================================
  // Helpers.
  // ===============================================================================================

  /** Asserts the encoded URL and type, then that the link decodes back to exactly the input. */
  private static void assertEncodes(String expectedUrl, String expectedType, Link input) {
    io.temporal.api.nexus.v1.Link actual = linkToNexusLink(input);
    assertNotNull("encoding returned null", actual);
    assertEquals("type", expectedType, actual.getType());
    assertEquals("url", expectedUrl, actual.getUrl());
    assertEquals("round trip", input, nexusLinkToLink(actual));
  }

  private static void assertDecodes(Link expected, String type, String url) {
    Link actual = nexusLinkToLink(nexusLink(type, url));
    assertNotNull("decoding returned null for " + url, actual);
    assertEquals(url, expected, actual);
  }

  private static void assertRejected(String type, String url) {
    try {
      assertNull("expected rejection of " + url, nexusLinkToLink(nexusLink(type, url)));
    } catch (RuntimeException e) {
      fail("expected rejection, but threw, for " + url + ": " + e);
    }
  }

  private static io.temporal.api.nexus.v1.Link nexusLink(String type, String url) {
    return io.temporal.api.nexus.v1.Link.newBuilder().setUrl(url).setType(type).build();
  }

  private static Link eventRef(String ns, String wfId, String runId, long eventId, EventType t) {
    Link.WorkflowEvent.EventReference.Builder ref =
        Link.WorkflowEvent.EventReference.newBuilder().setEventType(t);
    if (eventId != 0) {
      ref.setEventId(eventId);
    }
    return Link.newBuilder()
        .setWorkflowEvent(
            Link.WorkflowEvent.newBuilder()
                .setNamespace(ns)
                .setWorkflowId(wfId)
                .setRunId(runId)
                .setEventRef(ref))
        .build();
  }

  private static Link requestIdRef(
      String ns, String wfId, String runId, String requestId, EventType t) {
    return Link.newBuilder()
        .setWorkflowEvent(
            Link.WorkflowEvent.newBuilder()
                .setNamespace(ns)
                .setWorkflowId(wfId)
                .setRunId(runId)
                .setRequestIdRef(
                    Link.WorkflowEvent.RequestIdReference.newBuilder()
                        .setRequestId(requestId)
                        .setEventType(t)))
        .build();
  }

  private static Link workflow(String ns, String wfId, String runId, String reason) {
    return Link.newBuilder()
        .setWorkflow(
            Link.Workflow.newBuilder()
                .setNamespace(ns)
                .setWorkflowId(wfId)
                .setRunId(runId)
                .setReason(reason))
        .build();
  }

  private static Link nexusOperation(String ns, String opId, String runId) {
    return Link.newBuilder()
        .setNexusOperation(
            Link.NexusOperation.newBuilder().setNamespace(ns).setOperationId(opId).setRunId(runId))
        .build();
  }

  private static Link activity(String ns, String actId, String runId) {
    return Link.newBuilder()
        .setActivity(
            Link.Activity.newBuilder().setNamespace(ns).setActivityId(actId).setRunId(runId))
        .build();
  }
}
