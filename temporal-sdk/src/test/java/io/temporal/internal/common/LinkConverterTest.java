package io.temporal.internal.common;

import static io.temporal.internal.common.LinkConverter.nexusLinkToWorkflowEvent;
import static io.temporal.internal.common.LinkConverter.workflowEventToNexusLink;
import static org.junit.Assert.*;

import io.temporal.api.common.v1.Link;
import io.temporal.api.enums.v1.EventType;
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
}
