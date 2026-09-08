package io.temporal.internal.common;

import static io.temporal.internal.common.LinkConverter.activityToNexusLink;
import static io.temporal.internal.common.LinkConverter.linkToNexusLink;
import static io.temporal.internal.common.LinkConverter.nexusLinkToActivity;
import static io.temporal.internal.common.LinkConverter.nexusLinkToLink;
import static io.temporal.internal.common.LinkConverter.nexusLinkToNexusOperation;
import static io.temporal.internal.common.LinkConverter.nexusLinkToWorkflowEvent;
import static io.temporal.internal.common.LinkConverter.nexusLinkToWorkflowLink;
import static io.temporal.internal.common.LinkConverter.nexusOperationToNexusLink;
import static io.temporal.internal.common.LinkConverter.workflowEventToNexusLink;
import static io.temporal.internal.common.LinkConverter.workflowLinkToNexusLink;
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
  public void testConvertNexusOperationToNexus_ValidSlash() {
    Link.NexusOperation input =
        Link.NexusOperation.newBuilder()
            .setNamespace("ns")
            .setOperationId("op/id")
            .setRunId("run-id")
            .build();

    io.temporal.api.nexus.v1.Link expected =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl("temporal:///namespaces/ns/nexus-operations/op%2Fid/run-id/details")
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
  public void testConvertNexusToNexusOperation_ValidSlash() {
    io.temporal.api.nexus.v1.Link input =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl("temporal:///namespaces/ns/nexus-operations/op%2Fid/run-id/details")
            .setType("temporal.api.common.v1.Link.NexusOperation")
            .build();

    Link expected =
        Link.newBuilder()
            .setNexusOperation(
                Link.NexusOperation.newBuilder()
                    .setNamespace("ns")
                    .setOperationId("op/id")
                    .setRunId("run-id"))
            .build();

    assertEquals(expected, nexusLinkToNexusOperation(input));
  }

  @Test
  public void testConvertNexusToNexusOperation_WrongType() {
    io.temporal.api.nexus.v1.Link input =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl("temporal:///namespaces/ns/nexus-operations/op-id/run-id/details")
            .setType("temporal.api.common.v1.Link.WorkflowEvent")
            .build();

    assertNull(nexusLinkToNexusOperation(input));
  }

  @Test
  public void testConvertNexusToNexusOperation_InvalidScheme() {
    io.temporal.api.nexus.v1.Link input =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl("random:///namespaces/ns/nexus-operations/op-id/run-id/details")
            .setType("temporal.api.common.v1.Link.NexusOperation")
            .build();

    assertNull(nexusLinkToNexusOperation(input));
  }

  @Test
  public void testConvertNexusToNexusOperation_InvalidPathMissingDetails() {
    io.temporal.api.nexus.v1.Link input =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl("temporal:///namespaces/ns/nexus-operations/op-id/run-id/")
            .setType("temporal.api.common.v1.Link.NexusOperation")
            .build();

    assertNull(nexusLinkToNexusOperation(input));
  }

  @Test
  public void testConvertActivityToNexus_Valid() {
    Link.Activity input =
        Link.Activity.newBuilder()
            .setNamespace("ns")
            .setActivityId("act id/with+characters")
            .setRunId("run-id")
            .build();

    io.temporal.api.nexus.v1.Link expected =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl(
                "temporal:///namespaces/ns/activities/act%20id%2Fwith%2Bcharacters/run-id/details")
            .setType("temporal.api.common.v1.Link.Activity")
            .build();

    assertEquals(expected, activityToNexusLink(input));
  }

  @Test
  public void testConvertNexusToActivity_Valid() {
    io.temporal.api.nexus.v1.Link input =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl(
                "temporal:///namespaces/ns/activities/act%20id%2Fwith%2Bcharacters/run-id/details")
            .setType("temporal.api.common.v1.Link.Activity")
            .build();

    Link expected =
        Link.newBuilder()
            .setActivity(
                Link.Activity.newBuilder()
                    .setNamespace("ns")
                    .setActivityId("act id/with+characters")
                    .setRunId("run-id"))
            .build();

    assertEquals(expected, nexusLinkToActivity(input));
  }

  @Test
  public void testConvertNexusToActivity_InvalidPath() {
    io.temporal.api.nexus.v1.Link input =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl("temporal:///namespaces/ns/activities/act-id/run-id")
            .setType("temporal.api.common.v1.Link.Activity")
            .build();

    assertNull(nexusLinkToActivity(input));
  }

  @Test
  public void testNexusLinkToLink_WorkflowEventRoundTrip() {
    Link.WorkflowEvent we =
        Link.WorkflowEvent.newBuilder()
            .setNamespace("ns")
            .setWorkflowId("wf-id")
            .setRunId("run-id")
            .setEventRef(
                Link.WorkflowEvent.EventReference.newBuilder()
                    .setEventId(1)
                    .setEventType(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED))
            .build();

    io.temporal.api.nexus.v1.Link nexusLink = workflowEventToNexusLink(we);
    assertEquals("temporal.api.common.v1.Link.WorkflowEvent", nexusLink.getType());

    Link converted = nexusLinkToLink(nexusLink);
    assertNotNull(converted);
    assertEquals(Link.newBuilder().setWorkflowEvent(we).build(), converted);
  }

  @Test
  public void testNexusLinkToLink_NexusOperation() {
    io.temporal.api.nexus.v1.Link nexusLink =
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

    assertEquals(expected, nexusLinkToLink(nexusLink));
  }

  @Test
  public void testNexusLinkToLink_ActivityRoundTrip() {
    Link.Activity activity =
        Link.Activity.newBuilder()
            .setNamespace("ns")
            .setActivityId("act-id")
            .setRunId("run-id")
            .build();

    io.temporal.api.nexus.v1.Link nexusLink = activityToNexusLink(activity);
    assertEquals(Link.newBuilder().setActivity(activity).build(), nexusLinkToLink(nexusLink));
  }

  @Test
  public void testNexusLinkToLink_UnknownType() {
    io.temporal.api.nexus.v1.Link nexusLink =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl("temporal:///namespaces/ns/workflows/wf-id/run-id/history")
            .setType("unknown.type")
            .build();

    assertNull(nexusLinkToLink(nexusLink));
  }

  @Test
  public void testLinkToNexusLink_WorkflowEvent() {
    Link.WorkflowEvent we =
        Link.WorkflowEvent.newBuilder()
            .setNamespace("ns")
            .setWorkflowId("wf-id")
            .setRunId("run-id")
            .setEventRef(
                Link.WorkflowEvent.EventReference.newBuilder()
                    .setEventId(1)
                    .setEventType(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED))
            .build();

    io.temporal.api.nexus.v1.Link actual =
        linkToNexusLink(Link.newBuilder().setWorkflowEvent(we).build());
    assertEquals(workflowEventToNexusLink(we), actual);
  }

  @Test
  public void testLinkToNexusLink_NexusOperation() {
    Link.NexusOperation no =
        Link.NexusOperation.newBuilder()
            .setNamespace("ns")
            .setOperationId("op-id")
            .setRunId("run-id")
            .build();

    io.temporal.api.nexus.v1.Link actual =
        linkToNexusLink(Link.newBuilder().setNexusOperation(no).build());
    assertEquals(nexusOperationToNexusLink(no), actual);
  }

  @Test
  public void testLinkToNexusLink_Activity() {
    Link.Activity activity =
        Link.Activity.newBuilder()
            .setNamespace("ns")
            .setActivityId("act-id")
            .setRunId("run-id")
            .build();

    io.temporal.api.nexus.v1.Link actual =
        linkToNexusLink(Link.newBuilder().setActivity(activity).build());
    assertEquals(activityToNexusLink(activity), actual);
  }

  @Test
  public void testLinkToNexusLink_Empty() {
    assertNull(linkToNexusLink(Link.newBuilder().build()));
  }

  @Test
  public void testConvertWorkflowToNexus_Valid() {
    Link.Workflow input =
        Link.Workflow.newBuilder()
            .setNamespace("ns")
            .setWorkflowId("wf-id")
            .setRunId("run-id")
            .build();

    io.temporal.api.nexus.v1.Link expected =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl("temporal:///namespaces/ns/workflows/wf-id/run-id")
            .setType("temporal.api.common.v1.Link.Workflow")
            .build();

    assertEquals(expected, workflowLinkToNexusLink(input));
  }

  @Test
  public void testConvertWorkflowToNexus_ValidReason() {
    Link.Workflow input =
        Link.Workflow.newBuilder()
            .setNamespace("ns")
            .setWorkflowId("wf-id")
            .setRunId("run-id")
            .setReason("rejected update")
            .build();

    io.temporal.api.nexus.v1.Link expected =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl("temporal:///namespaces/ns/workflows/wf-id/run-id?reason=rejected+update")
            .setType("temporal.api.common.v1.Link.Workflow")
            .build();

    assertEquals(expected, workflowLinkToNexusLink(input));
  }

  @Test
  public void testConvertWorkflowToNexus_ValidSlash() {
    Link.Workflow input =
        Link.Workflow.newBuilder()
            .setNamespace("ns")
            .setWorkflowId("wf/id")
            .setRunId("run-id")
            .build();

    io.temporal.api.nexus.v1.Link expected =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl("temporal:///namespaces/ns/workflows/wf%2Fid/run-id")
            .setType("temporal.api.common.v1.Link.Workflow")
            .build();

    assertEquals(expected, workflowLinkToNexusLink(input));
  }

  @Test
  public void testConvertWorkflowToNexus_ValidSpace() throws UnsupportedEncodingException {
    Link.Workflow input =
        Link.Workflow.newBuilder()
            .setNamespace("ns")
            .setWorkflowId("wf id")
            .setRunId("run-id")
            .build();

    io.temporal.api.nexus.v1.Link expected =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl("temporal:///namespaces/ns/workflows/wf%20id/run-id")
            .setType("temporal.api.common.v1.Link.Workflow")
            .build();

    io.temporal.api.nexus.v1.Link actual = workflowLinkToNexusLink(input);
    assertEquals(expected, actual);
    // A space in the path has to survive as %20 rather than the '+' that form encoding would
    // produce, otherwise the link resolves to a different workflow ID.
    assertEquals(
        "temporal:///namespaces/ns/workflows/wf id/run-id",
        URLDecoder.decode(actual.getUrl(), StandardCharsets.UTF_8.toString()));
  }

  @Test
  public void testConvertNexusToWorkflow_Valid() {
    io.temporal.api.nexus.v1.Link input =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl("temporal:///namespaces/ns/workflows/wf-id/run-id")
            .setType("temporal.api.common.v1.Link.Workflow")
            .build();

    Link expected =
        Link.newBuilder()
            .setWorkflow(
                Link.Workflow.newBuilder()
                    .setNamespace("ns")
                    .setWorkflowId("wf-id")
                    .setRunId("run-id"))
            .build();

    assertEquals(expected, nexusLinkToWorkflowLink(input));
  }

  @Test
  public void testConvertNexusToWorkflow_ValidReason() {
    io.temporal.api.nexus.v1.Link input =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl("temporal:///namespaces/ns/workflows/wf-id/run-id?reason=rejected+update")
            .setType("temporal.api.common.v1.Link.Workflow")
            .build();

    Link expected =
        Link.newBuilder()
            .setWorkflow(
                Link.Workflow.newBuilder()
                    .setNamespace("ns")
                    .setWorkflowId("wf-id")
                    .setRunId("run-id")
                    .setReason("rejected update"))
            .build();

    assertEquals(expected, nexusLinkToWorkflowLink(input));
  }

  @Test
  public void testConvertNexusToWorkflow_WrongType() {
    io.temporal.api.nexus.v1.Link input =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl("temporal:///namespaces/ns/workflows/wf-id/run-id")
            .setType("temporal.api.common.v1.Link.WorkflowEvent")
            .build();

    assertNull(nexusLinkToWorkflowLink(input));
  }

  @Test
  public void testConvertNexusToWorkflow_InvalidScheme() {
    io.temporal.api.nexus.v1.Link input =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl("random:///namespaces/ns/workflows/wf-id/run-id")
            .setType("temporal.api.common.v1.Link.Workflow")
            .build();

    assertNull(nexusLinkToWorkflowLink(input));
  }

  @Test
  public void testConvertNexusToWorkflow_InvalidPathTrailingSegment() {
    // The workflow-event form addresses an event inside the workflow, so it must not be accepted
    // as a workflow link even when the type says otherwise.
    io.temporal.api.nexus.v1.Link input =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl("temporal:///namespaces/ns/workflows/wf-id/run-id/history")
            .setType("temporal.api.common.v1.Link.Workflow")
            .build();

    assertNull(nexusLinkToWorkflowLink(input));
  }

  @Test
  public void testConvertNexusToWorkflow_ReasonNotFirstQueryParam() {
    // The reason is located by key, not by position, so unrelated params ahead of it are skipped.
    io.temporal.api.nexus.v1.Link input =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl(
                "temporal:///namespaces/ns/workflows/wf-id/run-id?foo=bar&reason=Query+processed")
            .setType("temporal.api.common.v1.Link.Workflow")
            .build();

    assertEquals("Query processed", nexusLinkToWorkflowLink(input).getWorkflow().getReason());
  }

  @Test
  public void testConvertNexusToWorkflow_EmptyReasonValue() {
    io.temporal.api.nexus.v1.Link input =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl("temporal:///namespaces/ns/workflows/wf-id/run-id?reason=")
            .setType("temporal.api.common.v1.Link.Workflow")
            .build();

    assertEquals("", nexusLinkToWorkflowLink(input).getWorkflow().getReason());
  }

  @Test
  public void testConvertNexusToWorkflow_BareReasonKey() {
    // A key with no '=' must not blow up on the missing value.
    io.temporal.api.nexus.v1.Link input =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl("temporal:///namespaces/ns/workflows/wf-id/run-id?reason")
            .setType("temporal.api.common.v1.Link.Workflow")
            .build();

    assertEquals("", nexusLinkToWorkflowLink(input).getWorkflow().getReason());
  }

  @Test
  public void testConvertNexusToWorkflow_ReasonPrefixKeyIgnored() {
    // "reasonx" must not be treated as "reason".
    io.temporal.api.nexus.v1.Link input =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl("temporal:///namespaces/ns/workflows/wf-id/run-id?reasonx=nope")
            .setType("temporal.api.common.v1.Link.Workflow")
            .build();

    assertEquals("", nexusLinkToWorkflowLink(input).getWorkflow().getReason());
  }

  @Test
  public void testConvertNexusToWorkflow_EmptyUrl() {
    // A URL with no scheme must be reported as an invalid scheme rather than throwing.
    io.temporal.api.nexus.v1.Link input =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl("")
            .setType("temporal.api.common.v1.Link.Workflow")
            .build();

    assertNull(nexusLinkToWorkflowLink(input));
  }

  /**
   * A '+' in a path segment is a literal '+', not a space. Form decoding would turn it into a space
   * and point at a different execution.
   */
  @Test
  public void testConvertNexusToWorkflow_LiteralPlusInPathIsPreserved() {
    io.temporal.api.nexus.v1.Link input =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl("temporal:///namespaces/ns/workflows/a+b/run-id")
            .setType("temporal.api.common.v1.Link.Workflow")
            .build();

    assertEquals("a+b", nexusLinkToWorkflowLink(input).getWorkflow().getWorkflowId());

    // A percent-escaped space still decodes to a space.
    io.temporal.api.nexus.v1.Link spaceInput =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl("temporal:///namespaces/ns/workflows/a%20b/run-id")
            .setType("temporal.api.common.v1.Link.Workflow")
            .build();

    assertEquals("a b", nexusLinkToWorkflowLink(spaceInput).getWorkflow().getWorkflowId());

    // A '+' this SDK encoded itself does survive, because URLEncoder emits %2B.
    Link.Workflow w =
        Link.Workflow.newBuilder()
            .setNamespace("ns")
            .setWorkflowId("a+b")
            .setRunId("run-id")
            .build();
    assertEquals(
        Link.newBuilder().setWorkflow(w).build(),
        nexusLinkToWorkflowLink(workflowLinkToNexusLink(w)));
  }

  @Test
  public void testConvertNexusToWorkflow_InvalidPathMissingRunID() {
    io.temporal.api.nexus.v1.Link input =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl("temporal:///namespaces/ns/workflows/wf-id")
            .setType("temporal.api.common.v1.Link.Workflow")
            .build();

    assertNull(nexusLinkToWorkflowLink(input));
  }

  @Test
  public void testWorkflowLinkRoundTrip() {
    // Reserved characters in every field at once: the path segments are percent-escaped and the
    // reason is form-encoded, so a reason containing '=' and '&' must not be split as query syntax.
    Link.Workflow w =
        Link.Workflow.newBuilder()
            .setNamespace("ns/with/slash")
            .setWorkflowId("wf id with space")
            .setRunId("run-id")
            .setReason("reason with = and &")
            .build();

    io.temporal.api.nexus.v1.Link nexusLink = workflowLinkToNexusLink(w);
    assertEquals("temporal.api.common.v1.Link.Workflow", nexusLink.getType());
    assertEquals(Link.newBuilder().setWorkflow(w).build(), nexusLinkToWorkflowLink(nexusLink));
  }

  @Test
  public void testLinkToNexusLink_Workflow() {
    Link.Workflow w =
        Link.Workflow.newBuilder()
            .setNamespace("ns")
            .setWorkflowId("wf-id")
            .setRunId("run-id")
            .setReason("Query processed")
            .build();

    io.temporal.api.nexus.v1.Link actual =
        linkToNexusLink(Link.newBuilder().setWorkflow(w).build());
    assertEquals(workflowLinkToNexusLink(w), actual);
  }

  @Test
  public void testNexusLinkToLink_WorkflowRoundTrip() {
    Link.Workflow w =
        Link.Workflow.newBuilder()
            .setNamespace("ns")
            .setWorkflowId("wf-id")
            .setRunId("run-id")
            .setReason("Query processed")
            .build();

    io.temporal.api.nexus.v1.Link nexusLink = workflowLinkToNexusLink(w);
    Link converted = nexusLinkToLink(nexusLink);
    assertNotNull(converted);
    assertEquals(Link.newBuilder().setWorkflow(w).build(), converted);
  }
}
