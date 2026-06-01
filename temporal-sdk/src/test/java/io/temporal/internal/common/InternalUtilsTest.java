package io.temporal.internal.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import io.nexusrpc.Link;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.internal.client.NexusStartWorkflowRequest;
import io.temporal.internal.nexus.CurrentNexusOperationContext;
import io.temporal.internal.nexus.InternalNexusOperationContext;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class InternalUtilsTest {
  private static final String NAMESPACE = "test-namespace";

  @Before
  public void setUp() {
    Scope metricsScope = new RootScopeBuilder().reportEvery(com.uber.m3.util.Duration.ofMillis(10));
    CurrentNexusOperationContext.set(
        new InternalNexusOperationContext(
            NAMESPACE, "tq", "endpoint", metricsScope, mock(WorkflowClient.class)));
  }

  @After
  public void tearDown() {
    CurrentNexusOperationContext.unset();
  }

  /**
   * Regression guard for the {@code LinkConverter} generalization: inbound nexus links of every
   * known type (WorkflowEvent + NexusOperation) must flow through {@code createNexusBoundStub} to
   * the resulting workflow options. Unknown types must still be filtered out.
   *
   * <p>Before this PR, only WorkflowEvent-typed links were forwarded; NexusOperation links were
   * silently dropped at the type guard in {@code InternalUtils.createNexusBoundStub}.
   */
  @Test
  public void createNexusBoundStub_dispatchesAllKnownLinkTypes() throws URISyntaxException {
    WorkflowStub stub = mock(WorkflowStub.class);
    WorkflowOptions stubOptions = WorkflowOptions.newBuilder().setWorkflowId("wf-id").build();
    when(stub.getOptions()).thenReturn(Optional.of(stubOptions));
    when(stub.newInstance(any(WorkflowOptions.class))).thenReturn(mock(WorkflowStub.class));

    Link workflowEventInbound =
        Link.newBuilder()
            .setType("temporal.api.common.v1.Link.WorkflowEvent")
            .setUri(
                new URI(
                    "temporal:///namespaces/ns/workflows/caller-wf/caller-run/history?eventID=1&eventType=NexusOperationScheduled&referenceType=EventReference"))
            .build();
    Link nexusOpInbound =
        Link.newBuilder()
            .setType("temporal.api.common.v1.Link.NexusOperation")
            .setUri(new URI("temporal:///namespaces/ns/nexus-operations/op-id/run-id/details"))
            .build();
    Link unknownInbound =
        Link.newBuilder()
            .setType("not.a.real.Type")
            .setUri(new URI("temporal:///some/unknown/path"))
            .build();

    NexusStartWorkflowRequest request =
        new NexusStartWorkflowRequest(
            "req-id",
            "", // no callback URL — exercises the link path without the callback-headers branch
            Collections.emptyMap(),
            "tq",
            Arrays.asList(workflowEventInbound, nexusOpInbound, unknownInbound));

    InternalUtils.createNexusBoundStub(stub, request);

    ArgumentCaptor<WorkflowOptions> captor = ArgumentCaptor.forClass(WorkflowOptions.class);
    org.mockito.Mockito.verify(stub).newInstance(captor.capture());
    WorkflowOptions captured = captor.getValue();
    assertNotNull("expected links to be set on bound options", captured.getLinks());
    assertEquals(
        "expected WorkflowEvent + NexusOperation to flow through; unknown filtered out",
        2,
        captured.getLinks().size());
    assertTrue(
        "expected one WorkflowEvent variant",
        captured.getLinks().stream().anyMatch(l -> l.hasWorkflowEvent()));
    assertTrue(
        "expected one NexusOperation variant",
        captured.getLinks().stream().anyMatch(l -> l.hasNexusOperation()));
  }
}
