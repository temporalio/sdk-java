package io.temporal.internal.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.interceptors.Header;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class WorkflowClientRequestFactoryTest {
  @Test
  public void requestsUseExplicitNamespaceAndIdentity() {
    WorkflowClientRequestFactory factory =
        new WorkflowClientRequestFactory(
            "test-namespace", "test-identity", Collections.emptyList());
    StartWorkflowExecutionRequest start = start(factory, null);
    assertEquals("test-namespace", start.getNamespace());
    assertEquals("test-identity", start.getIdentity());

    SignalWithStartWorkflowExecutionRequest signalWithStart =
        factory.newSignalWithStartWorkflowExecutionRequest(start, "signal", null).build();
    assertEquals("test-namespace", signalWithStart.getNamespace());
    assertEquals("test-identity", signalWithStart.getIdentity());
    assertEquals(
        "test-namespace",
        factory
            .newHistoryLongPollRequest(WorkflowExecution.getDefaultInstance(), ByteString.EMPTY)
            .getNamespace());
  }

  @Test
  public void clientContextPropagatorListRetainsReferenceSemantics() {
    List<ContextPropagator> propagators = new ArrayList<>();
    WorkflowClientRequestFactory factory =
        new WorkflowClientRequestFactory("test-namespace", "test-identity", propagators);
    assertFalse(start(factory, null).getHeader().containsFields("trace"));

    propagators.add(propagator("client"));

    assertEquals(payload("client"), start(factory, null).getHeader().getFieldsOrThrow("trace"));
  }

  @Test
  public void workflowContextPropagatorsOverrideClientDefaultsIncludingEmptyList() {
    WorkflowClientRequestFactory factory =
        new WorkflowClientRequestFactory(
            "test-namespace", "test-identity", Collections.singletonList(propagator("client")));
    assertEquals(
        payload("workflow"),
        start(factory, Collections.singletonList(propagator("workflow")))
            .getHeader()
            .getFieldsOrThrow("trace"));
    assertFalse(start(factory, Collections.emptyList()).getHeader().containsFields("trace"));
  }

  private static StartWorkflowExecutionRequest start(
      WorkflowClientRequestFactory factory, List<ContextPropagator> workflowPropagators) {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setTaskQueue("test-queue")
            .setWorkflowRunTimeout(Duration.ofMinutes(1))
            .setWorkflowExecutionTimeout(Duration.ofMinutes(1))
            .setWorkflowTaskTimeout(Duration.ofSeconds(10))
            .setContextPropagators(workflowPropagators)
            .build();
    return factory
        .newStartWorkflowExecutionRequest(
            "workflow-id", "workflow-type", Header.empty(), options, null, null, null)
        .build();
  }

  private static ContextPropagator propagator(String value) {
    ContextPropagator propagator = mock(ContextPropagator.class);
    when(propagator.getCurrentContext()).thenReturn(value);
    when(propagator.serializeContext(value))
        .thenReturn(Collections.singletonMap("trace", payload(value)));
    return propagator;
  }

  private static Payload payload(String value) {
    return DefaultDataConverter.newDefaultInstance().toPayload(value).get();
  }
}
