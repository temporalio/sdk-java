package io.temporal.internal.common;

import static io.temporal.internal.common.LinkConverter.commonLinkToNexusLink;
import static io.temporal.internal.common.LinkConverter.nexusLinkToCommonLink;
import static io.temporal.internal.common.LinkConverter.nexusLinkToNexusOperation;
import static io.temporal.internal.common.LinkConverter.nexusLinkToWorkflowEvent;
import static io.temporal.internal.common.LinkConverter.nexusOperationToNexusLink;
import static io.temporal.internal.common.LinkConverter.workflowEventToNexusLink;
import static org.junit.Assert.*;

import io.temporal.api.common.v1.Link;
import io.temporal.api.enums.v1.EventType;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class LinkConverterTest {

  @Test
  public void testConvertWorkflowEventToNexus_Valid() {
    Link.WorkflowEvent input =
        Link.WorkflowEvent.newBuilder()
            .setNamespace("ns")
            .setWorkflowId("wf-id")
            .setRunId("run-id")
            .setEventRef(
                Link.WorkflowEvent.EventReference.newBuilder()
                    .setEventId(1)
                    .setEventType(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED))
            .build();

    io.temporal.api.nexus.v1.Link expected =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl(
                "temporal:///namespaces/ns/workflows/wf-id/run-id/history?referenceType=EventReference&eventID=1&eventType=WorkflowExecutionStarted")
            .setType("temporal.api.common.v1.Link.WorkflowEvent")
            .build();

    io.temporal.api.nexus.v1.Link actual = workflowEventToNexusLink(input);
    assertEquals(expected, actual);

    input =
        input.toBuilder()
            .setRequestIdRef(
                Link.WorkflowEvent.RequestIdReference.newBuilder()
                    .setRequestId("random-request-id")
                    .setEventType(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_OPTIONS_UPDATED))
            .build();
    expected =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl(
                "temporal:///namespaces/ns/workflows/wf-id/run-id/history?referenceType=RequestIdReference&requestID=random-request-id&eventType=WorkflowExecutionOptionsUpdated")
            .setType("temporal.api.common.v1.Link.WorkflowEvent")
            .build();
    actual = workflowEventToNexusLink(input);
    assertEquals(expected, actual);
  }

  @Test
  public void testConvertWorkflowEventToNexus_ValidAngle() {
    Link.WorkflowEvent input =
        Link.WorkflowEvent.newBuilder()
            .setNamespace("ns")
            .setWorkflowId("wf-id>")
            .setRunId("run-id")
            .setEventRef(
                Link.WorkflowEvent.EventReference.newBuilder()
                    .setEventId(1)
                    .setEventType(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED))
            .build();

    io.temporal.api.nexus.v1.Link expected =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl(
                "temporal:///namespaces/ns/workflows/wf-id%3E/run-id/history?referenceType=EventReference&eventID=1&eventType=WorkflowExecutionStarted")
            .setType("temporal.api.common.v1.Link.WorkflowEvent")
            .build();

    io.temporal.api.nexus.v1.Link actual = workflowEventToNexusLink(input);
    assertEquals(expected, actual);
  }

  @Test
  public void testConvertWorkflowEventToNexus_ValidSlash() {
    Link.WorkflowEvent input =
        Link.WorkflowEvent.newBuilder()
            .setNamespace("ns")
            .setWorkflowId("wf-id/")
            .setRunId("run-id")
            .setEventRef(
                Link.WorkflowEvent.EventReference.newBuilder()
                    .setEventId(1)
                    .setEventType(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED))
            .build();

    io.temporal.api.nexus.v1.Link expected =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl(
                "temporal:///namespaces/ns/workflows/wf-id%2F/run-id/history?referenceType=EventReference&eventID=1&eventType=WorkflowExecutionStarted")
            .setType("temporal.api.common.v1.Link.WorkflowEvent")
            .build();

    io.temporal.api.nexus.v1.Link actual = workflowEventToNexusLink(input);
    assertEquals(expected, actual);
  }

  @Test
  public void testConvertWorkflowEventToNexus_ValidSpace() throws UnsupportedEncodingException {
    Link.WorkflowEvent input =
        Link.WorkflowEvent.newBuilder()
            .setNamespace("ns")
            .setWorkflowId("wf space+plus")
            .setRunId("run-id")
            .setEventRef(
                Link.WorkflowEvent.EventReference.newBuilder()
                    .setEventId(1)
                    .setEventType(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED))
            .build();

    io.temporal.api.nexus.v1.Link expected =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl(
                "temporal:///namespaces/ns/workflows/wf%20space%2Bplus/run-id/history?referenceType=EventReference&eventID=1&eventType=WorkflowExecutionStarted")
            .setType("temporal.api.common.v1.Link.WorkflowEvent")
            .build();

    io.temporal.api.nexus.v1.Link actual = workflowEventToNexusLink(input);
    assertEquals(expected, actual);

    String decoded = URLDecoder.decode(actual.getUrl(), StandardCharsets.UTF_8.toString());
    assertEquals(
        "temporal:///namespaces/ns/workflows/wf space+plus/run-id/history?referenceType=EventReference&eventID=1&eventType=WorkflowExecutionStarted",
        decoded);
  }

  @Test
  public void testConvertWorkflowEventToNexus_ValidEventIDMissing() {
    Link.WorkflowEvent input =
        Link.WorkflowEvent.newBuilder()
            .setNamespace("ns")
            .setWorkflowId("wf-id")
            .setRunId("run-id")
            .setEventRef(
                Link.WorkflowEvent.EventReference.newBuilder()
                    .setEventType(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED))
            .build();

    io.temporal.api.nexus.v1.Link expected =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl(
                "temporal:///namespaces/ns/workflows/wf-id/run-id/history?referenceType=EventReference&eventType=WorkflowExecutionStarted")
            .setType("temporal.api.common.v1.Link.WorkflowEvent")
            .build();

    io.temporal.api.nexus.v1.Link actual = workflowEventToNexusLink(input);
    assertEquals(expected, actual);
  }

  @Test
  public void testConvertNexusToWorkflowEvent_Valid() {
    io.temporal.api.nexus.v1.Link input =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl(
                "temporal:///namespaces/ns/workflows/wf-id/run-id/history?eventID=1&eventType=WorkflowExecutionStarted&referenceType=EventReference")
            .setType("temporal.api.common.v1.Link.WorkflowEvent")
            .build();

    Link expected =
        Link.newBuilder()
            .setWorkflowEvent(
                Link.WorkflowEvent.newBuilder()
                    .setNamespace("ns")
                    .setWorkflowId("wf-id")
                    .setRunId("run-id")
                    .setEventRef(
                        Link.WorkflowEvent.EventReference.newBuilder()
                            .setEventId(1)
                            .setEventType(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)))
            .build();

    Link actual = nexusLinkToWorkflowEvent(input);
    assertEquals(expected, actual);

    input =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl(
                "temporal:///namespaces/ns/workflows/wf-id/run-id/history?referenceType=RequestIdReference&requestID=random-request-id&eventType=WorkflowExecutionOptionsUpdated")
            .setType("temporal.api.common.v1.Link.WorkflowEvent")
            .build();

    expected =
        Link.newBuilder()
            .setWorkflowEvent(
                Link.WorkflowEvent.newBuilder()
                    .setNamespace("ns")
                    .setWorkflowId("wf-id")
                    .setRunId("run-id")
                    .setRequestIdRef(
                        Link.WorkflowEvent.RequestIdReference.newBuilder()
                            .setRequestId("random-request-id")
                            .setEventType(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_OPTIONS_UPDATED)))
            .build();

    actual = nexusLinkToWorkflowEvent(input);
    assertEquals(expected, actual);
  }

  @Test
  public void testConvertNexusToWorkflowEvent_ValidLongEventType() {
    io.temporal.api.nexus.v1.Link input =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl(
                "temporal:///namespaces/ns/workflows/wf-id/run-id/history?eventID=1&eventType=EVENT_TYPE_WORKFLOW_EXECUTION_STARTED&referenceType=EventReference")
            .setType("temporal.api.common.v1.Link.WorkflowEvent")
            .build();

    Link expected =
        Link.newBuilder()
            .setWorkflowEvent(
                Link.WorkflowEvent.newBuilder()
                    .setNamespace("ns")
                    .setWorkflowId("wf-id")
                    .setRunId("run-id")
                    .setEventRef(
                        Link.WorkflowEvent.EventReference.newBuilder()
                            .setEventId(1)
                            .setEventType(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)))
            .build();

    Link actual = nexusLinkToWorkflowEvent(input);
    assertEquals(expected, actual);
  }

  @Test
  public void testConvertNexusToWorkflowEvent_ValidAngle() {
    io.temporal.api.nexus.v1.Link input =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl(
                "temporal:///namespaces/ns/workflows/wf-id%3E/run-id/history?eventID=1&eventType=WorkflowExecutionStarted&referenceType=EventReference")
            .setType("temporal.api.common.v1.Link.WorkflowEvent")
            .build();

    Link expected =
        Link.newBuilder()
            .setWorkflowEvent(
                Link.WorkflowEvent.newBuilder()
                    .setNamespace("ns")
                    .setWorkflowId("wf-id>")
                    .setRunId("run-id")
                    .setEventRef(
                        Link.WorkflowEvent.EventReference.newBuilder()
                            .setEventId(1)
                            .setEventType(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)))
            .build();

    Link actual = nexusLinkToWorkflowEvent(input);
    assertEquals(expected, actual);
  }

  @Test
  public void testConvertNexusToWorkflowEvent_ValidSlash() {
    io.temporal.api.nexus.v1.Link input =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl(
                "temporal:///namespaces/ns/workflows/wf-id%2F/run-id/history?eventID=1&eventType=WorkflowExecutionStarted&referenceType=EventReference")
            .setType("temporal.api.common.v1.Link.WorkflowEvent")
            .build();

    Link expected =
        Link.newBuilder()
            .setWorkflowEvent(
                Link.WorkflowEvent.newBuilder()
                    .setNamespace("ns")
                    .setWorkflowId("wf-id/")
                    .setRunId("run-id")
                    .setEventRef(
                        Link.WorkflowEvent.EventReference.newBuilder()
                            .setEventId(1)
                            .setEventType(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)))
            .build();

    Link actual = nexusLinkToWorkflowEvent(input);
    assertEquals(expected, actual);
  }

  @Test
  public void testConvertNexusToWorkflowEvent_ValidEventIDMissing() {
    io.temporal.api.nexus.v1.Link input =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl(
                "temporal:///namespaces/ns/workflows/wf-id/run-id/history?eventType=WorkflowExecutionStarted&referenceType=EventReference")
            .setType("temporal.api.common.v1.Link.WorkflowEvent")
            .build();

    Link expected =
        Link.newBuilder()
            .setWorkflowEvent(
                Link.WorkflowEvent.newBuilder()
                    .setNamespace("ns")
                    .setWorkflowId("wf-id")
                    .setRunId("run-id")
                    .setEventRef(
                        Link.WorkflowEvent.EventReference.newBuilder()
                            .setEventType(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)))
            .build();

    Link actual = nexusLinkToWorkflowEvent(input);
    assertEquals(expected, actual);
  }

  @Test
  public void testConvertNexusToWorkflowEvent_InvalidScheme() {
    io.temporal.api.nexus.v1.Link input =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl(
                "test:///namespaces/ns/workflows/wf-id/run-id/history?eventType=WorkflowExecutionStarted&referenceType=EventReference")
            .setType("temporal.api.common.v1.Link.WorkflowEvent")
            .build();

    assertNull(nexusLinkToWorkflowEvent(input));
  }

  @Test
  public void testConvertNexusToWorkflowEvent_InvalidPathMissingHistory() {
    io.temporal.api.nexus.v1.Link input =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl(
                "temporal:///namespaces/ns/workflows/wf-id/run-id/?eventType=WorkflowExecutionStarted&referenceType=EventReference")
            .setType("temporal.api.common.v1.Link.WorkflowEvent")
            .build();

    assertNull(nexusLinkToWorkflowEvent(input));
  }

  @Test
  public void testConvertNexusToWorkflowEvent_InvalidPathMissingNamespace() {
    io.temporal.api.nexus.v1.Link input =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl(
                "temporal:///namespaces//workflows/wf-id/run-id/history?eventType=WorkflowExecutionStarted&referenceType=EventReference")
            .setType("temporal.api.common.v1.Link.WorkflowEvent")
            .build();

    assertNull(nexusLinkToWorkflowEvent(input));
  }

  @Test
  public void testConvertNexusToWorkflowEvent_InvalidEventType() {
    io.temporal.api.nexus.v1.Link input =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl(
                "temporal:///namespaces/ns/workflows/wf-id/run-id/history?eventType=WorkflowExecution&referenceType=EventReference")
            .setType("temporal.api.common.v1.Link.WorkflowEvent")
            .build();

    assertNull(nexusLinkToWorkflowEvent(input));
  }

  // ── NexusOperation encode / decode ───────────────────────────────────────────────────────

  @Test
  public void testConvertNexusOperationToNexus_Valid() {
    Link.NexusOperation input =
        Link.NexusOperation.newBuilder()
            .setNamespace("ns")
            .setOperationId("op-id")
            .setRunId("run-id")
            .build();

    io.temporal.api.nexus.v1.Link expected =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl("temporal:///namespaces/ns/nexus-operations/op-id/run-id/details")
            .setType("temporal.api.common.v1.Link.NexusOperation")
            .build();

    assertEquals(expected, nexusOperationToNexusLink(input));
  }

  @Test
  public void testConvertNexusToNexusOperation_Valid() {
    io.temporal.api.nexus.v1.Link input =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl("temporal:///namespaces/ns/nexus-operations/op-id/run-id/details")
            .setType("temporal.api.common.v1.Link.NexusOperation")
            .build();

    Link expected =
        Link.newBuilder()
            .setNexusOperation(
                Link.NexusOperation.newBuilder()
                    .setNamespace("ns")
                    .setOperationId("op-id")
                    .setRunId("run-id"))
            .build();

    assertEquals(expected, nexusLinkToNexusOperation(input));
  }

  @Test
  public void testNexusOperationRoundTrip() {
    Link original =
        Link.newBuilder()
            .setNexusOperation(
                Link.NexusOperation.newBuilder()
                    .setNamespace("ns")
                    .setOperationId("op-id")
                    .setRunId("run-id"))
            .build();

    io.temporal.api.nexus.v1.Link encoded = commonLinkToNexusLink(original);
    Link decoded = nexusLinkToCommonLink(encoded);
    assertEquals(original, decoded);
  }

  @Test
  public void testNexusOperationRoundTrip_OperationIdWithSpecialChars() {
    // operationId is the field the encoder applies the `+ → %20` workaround to (matching the
    // workflowId precedent in WorkflowEvent). Verify spaces, '+', and '/' round-trip cleanly.
    Link original =
        Link.newBuilder()
            .setNexusOperation(
                Link.NexusOperation.newBuilder()
                    .setNamespace("ns")
                    .setOperationId("op with+special/chars")
                    .setRunId("run-id"))
            .build();

    io.temporal.api.nexus.v1.Link encoded = commonLinkToNexusLink(original);
    Link decoded = nexusLinkToCommonLink(encoded);
    assertEquals(original, decoded);
  }

  @Test
  public void testConvertNexusToNexusOperation_ExtraTokensAfterDetails() {
    // Defends against silently lossy parsing if the server later extends the path beyond
    // /details/... (e.g. /details/{event_id}). Older SDKs should reject rather than drop tokens.
    io.temporal.api.nexus.v1.Link input =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl("temporal:///namespaces/ns/nexus-operations/op-id/run-id/details/extra/junk")
            .setType("temporal.api.common.v1.Link.NexusOperation")
            .build();

    assertNull(nexusLinkToNexusOperation(input));
  }

  @Test
  public void testConvertNexusToNexusOperation_InvalidScheme() {
    io.temporal.api.nexus.v1.Link input =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl("http:///namespaces/ns/nexus-operations/op-id/run-id/details")
            .setType("temporal.api.common.v1.Link.NexusOperation")
            .build();

    assertNull(nexusLinkToNexusOperation(input));
  }

  @Test
  public void testConvertNexusToNexusOperation_InvalidPath() {
    io.temporal.api.nexus.v1.Link input =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl("temporal:///namespaces/ns/workflows/wf-id/run-id/history")
            .setType("temporal.api.common.v1.Link.NexusOperation")
            .build();

    assertNull(nexusLinkToNexusOperation(input));
  }

  // ── Dispatchers ──────────────────────────────────────────────────────────────────────────

  @Test
  public void testNexusLinkToCommonLink_DispatchesOnType() {
    io.temporal.api.nexus.v1.Link workflowEventLink =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl(
                "temporal:///namespaces/ns/workflows/wf-id/run-id/history?eventID=1&eventType=WorkflowExecutionStarted&referenceType=EventReference")
            .setType("temporal.api.common.v1.Link.WorkflowEvent")
            .build();
    Link decodedWorkflowEvent = nexusLinkToCommonLink(workflowEventLink);
    assertNotNull(decodedWorkflowEvent);
    assertTrue(decodedWorkflowEvent.hasWorkflowEvent());

    io.temporal.api.nexus.v1.Link nexusOpLink =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl("temporal:///namespaces/ns/nexus-operations/op-id/run-id/details")
            .setType("temporal.api.common.v1.Link.NexusOperation")
            .build();
    Link decodedNexusOp = nexusLinkToCommonLink(nexusOpLink);
    assertNotNull(decodedNexusOp);
    assertTrue(decodedNexusOp.hasNexusOperation());
  }

  @Test
  public void testNexusLinkToCommonLink_UnknownTypeReturnsNull() {
    io.temporal.api.nexus.v1.Link input =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl("temporal:///some/other/path")
            .setType("not.a.real.Type")
            .build();

    assertNull(nexusLinkToCommonLink(input));
  }

  @Test
  public void testCommonLinkToNexusLink_DispatchesOnVariant() {
    Link workflowEvent =
        Link.newBuilder()
            .setWorkflowEvent(
                Link.WorkflowEvent.newBuilder()
                    .setNamespace("ns")
                    .setWorkflowId("wf-id")
                    .setRunId("run-id")
                    .setEventRef(
                        Link.WorkflowEvent.EventReference.newBuilder()
                            .setEventId(1)
                            .setEventType(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)))
            .build();
    io.temporal.api.nexus.v1.Link encodedWorkflowEvent = commonLinkToNexusLink(workflowEvent);
    assertNotNull(encodedWorkflowEvent);
    assertEquals("temporal.api.common.v1.Link.WorkflowEvent", encodedWorkflowEvent.getType());

    Link nexusOp =
        Link.newBuilder()
            .setNexusOperation(
                Link.NexusOperation.newBuilder()
                    .setNamespace("ns")
                    .setOperationId("op-id")
                    .setRunId("run-id"))
            .build();
    io.temporal.api.nexus.v1.Link encodedNexusOp = commonLinkToNexusLink(nexusOp);
    assertNotNull(encodedNexusOp);
    assertEquals("temporal.api.common.v1.Link.NexusOperation", encodedNexusOp.getType());
  }

  @Test
  public void testCommonLinkToNexusLink_UnsupportedVariantReturnsNull() {
    // Activity and BatchJob variants are not yet encoded by either the SDK or the canonical
    // server implementation; the dispatcher logs and returns null until they are.
    Link activity =
        Link.newBuilder()
            .setActivity(
                Link.Activity.newBuilder()
                    .setNamespace("ns")
                    .setActivityId("act-id")
                    .setRunId("run-id"))
            .build();
    assertNull(commonLinkToNexusLink(activity));

    Link batchJob =
        Link.newBuilder().setBatchJob(Link.BatchJob.newBuilder().setJobId("job-id")).build();
    assertNull(commonLinkToNexusLink(batchJob));

    assertNull(commonLinkToNexusLink(Link.getDefaultInstance()));
  }
}
