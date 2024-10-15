/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
                "temporal:///namespaces/ns/workflows/wf-id/run-id/history?eventID=1&eventType=EVENT_TYPE_WORKFLOW_EXECUTION_STARTED&referenceType=EventReference")
            .setType("temporal.api.common.v1.Link.WorkflowEvent")
            .build();

    io.temporal.api.nexus.v1.Link actual = workflowEventToNexusLink(input);
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
                "temporal:///namespaces/ns/workflows/wf-id%3E/run-id/history?eventID=1&eventType=EVENT_TYPE_WORKFLOW_EXECUTION_STARTED&referenceType=EventReference")
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
                "temporal:///namespaces/ns/workflows/wf-id%2F/run-id/history?eventID=1&eventType=EVENT_TYPE_WORKFLOW_EXECUTION_STARTED&referenceType=EventReference")
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
                "temporal:///namespaces/ns/workflows/wf-id/run-id/history?eventType=EVENT_TYPE_WORKFLOW_EXECUTION_STARTED&referenceType=EventReference")
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
                "temporal:///namespaces/ns/workflows/wf-id%3E/run-id/history?eventID=1&eventType=EVENT_TYPE_WORKFLOW_EXECUTION_STARTED&referenceType=EventReference")
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
                "temporal:///namespaces/ns/workflows/wf-id%2F/run-id/history?eventID=1&eventType=EVENT_TYPE_WORKFLOW_EXECUTION_STARTED&referenceType=EventReference")
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
                "temporal:///namespaces/ns/workflows/wf-id/run-id/history?eventType=EVENT_TYPE_WORKFLOW_EXECUTION_STARTED&referenceType=EventReference")
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
                "test:///namespaces/ns/workflows/wf-id/run-id/history?eventType=EVENT_TYPE_WORKFLOW_EXECUTION_STARTED&referenceType=EventReference")
            .setType("temporal.api.common.v1.Link.WorkflowEvent")
            .build();

    assertNull(nexusLinkToWorkflowEvent(input));
  }

  @Test
  public void testConvertNexusToWorkflowEvent_InvalidPathMissingHistory() {
    io.temporal.api.nexus.v1.Link input =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl(
                "temporal:///namespaces/ns/workflows/wf-id/run-id/?eventType=EVENT_TYPE_WORKFLOW_EXECUTION_STARTED&referenceType=EventReference")
            .setType("temporal.api.common.v1.Link.WorkflowEvent")
            .build();

    assertNull(nexusLinkToWorkflowEvent(input));
  }

  @Test
  public void testConvertNexusToWorkflowEvent_InvalidPathMissingNamespace() {
    io.temporal.api.nexus.v1.Link input =
        io.temporal.api.nexus.v1.Link.newBuilder()
            .setUrl(
                "temporal:///namespaces//workflows/wf-id/run-id/history?eventType=EVENT_TYPE_WORKFLOW_EXECUTION_STARTED&referenceType=EventReference")
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
