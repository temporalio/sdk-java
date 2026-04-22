package io.temporal.internal.client;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.*;

import io.temporal.api.common.v1.Payload;
import io.temporal.api.workflowservice.v1.StartActivityExecutionRequest;
import io.temporal.api.workflowservice.v1.StartActivityExecutionResponse;
import io.temporal.client.ActivityClientOptions;
import io.temporal.client.StartActivityOptions;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.interceptors.ActivityClientCallsInterceptor.StartActivityInput;
import io.temporal.common.interceptors.Header;
import io.temporal.internal.client.external.GenericWorkflowClient;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * Verifies that ContextPropagators registered on ActivityClientOptions are applied to the gRPC
 * header on startActivity. Currently fails because RootActivityClientInvoker passes null for
 * propagators to HeaderUtils.toHeaderGrpc.
 */
public class RootActivityClientInvokerContextPropagatorTest {

  private static final String PROPAGATOR_KEY = "x-test-key";
  private static final String PROPAGATOR_VALUE = "test-value-from-propagator";

  /** A propagator that always injects a fixed key/value pair. */
  static class FixedValuePropagator implements ContextPropagator {
    @Override
    public String getName() {
      return "fixed-value-propagator";
    }

    @Override
    public Object getCurrentContext() {
      return PROPAGATOR_VALUE;
    }

    @Override
    public Map<String, Payload> serializeContext(Object context) {
      return Collections.singletonMap(
          PROPAGATOR_KEY, DefaultDataConverter.STANDARD_INSTANCE.toPayload(context).get());
    }

    @Override
    public Object deserializeContext(Map<String, Payload> context) {
      return context.get(PROPAGATOR_KEY);
    }

    @Override
    public void setCurrentContext(Object context) {}
  }

  @Test
  public void contextPropagatorValueAppearsInStartActivityHeader() {
    GenericWorkflowClient mockClient = mock(GenericWorkflowClient.class);
    when(mockClient.startActivity(any()))
        .thenReturn(StartActivityExecutionResponse.newBuilder().setRunId("run-1").build());

    ActivityClientOptions options =
        ActivityClientOptions.newBuilder()
            .setNamespace("test-ns")
            .setContextPropagators(Collections.singletonList(new FixedValuePropagator()))
            .build();

    RootActivityClientInvoker invoker = new RootActivityClientInvoker(mockClient, options);

    StartActivityOptions startOptions =
        StartActivityOptions.newBuilder()
            .setId("act-1")
            .setTaskQueue("tq")
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .build();

    invoker.startActivity(
        new StartActivityInput(
            "MyActivity", Collections.emptyList(), startOptions, Header.empty()));

    ArgumentCaptor<StartActivityExecutionRequest> captor =
        forClass(StartActivityExecutionRequest.class);
    verify(mockClient).startActivity(captor.capture());

    Map<String, io.temporal.api.common.v1.Payload> headerFields =
        captor.getValue().getHeader().getFieldsMap();

    // The propagator must have injected PROPAGATOR_KEY into the outgoing header.
    // This assertion fails because RootActivityClientInvoker passes null for propagators
    // to HeaderUtils.toHeaderGrpc, so the header is always empty.
    assertEquals(
        "Context propagator key must appear in the gRPC request header",
        true,
        headerFields.containsKey(PROPAGATOR_KEY));
  }
}
