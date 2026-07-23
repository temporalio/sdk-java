package io.temporal.internal.client;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.uber.m3.tally.NoopScope;
import io.temporal.api.common.v1.Link;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.workflowservice.v1.StartActivityExecutionRequest;
import io.temporal.api.workflowservice.v1.StartActivityExecutionResponse;
import io.temporal.client.ActivityClientOptions;
import io.temporal.client.StartActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.common.interceptors.ActivityClientCallsInterceptor.StartActivityInput;
import io.temporal.common.interceptors.Header;
import io.temporal.internal.client.external.GenericWorkflowClient;
import io.temporal.internal.nexus.CurrentNexusOperationContext;
import io.temporal.internal.nexus.InternalNexusOperationContext;
import io.temporal.internal.nexus.NexusOperationMetadata;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/** Unit tests for Nexus metadata propagation by {@link RootActivityClientInvoker}. */
public class RootActivityClientInvokerTest {

  private static final String NAMESPACE = "test-namespace";

  private GenericWorkflowClient genericClient;
  private RootActivityClientInvoker invoker;
  private InternalNexusOperationContext nexusContext;

  @Before
  public void setUp() {
    genericClient = mock(GenericWorkflowClient.class);
    when(genericClient.startActivity(any(StartActivityExecutionRequest.class)))
        .thenReturn(
            StartActivityExecutionResponse.newBuilder().setRunId("activity-run-id").build());
    invoker =
        new RootActivityClientInvoker(
            genericClient,
            ActivityClientOptions.newBuilder()
                .setNamespace(NAMESPACE)
                .setIdentity("test-identity")
                .build());
    nexusContext =
        new InternalNexusOperationContext(
            NAMESPACE,
            "test-task-queue",
            "test-endpoint",
            new NoopScope(),
            mock(WorkflowClient.class));
    CurrentNexusOperationContext.set(nexusContext);
  }

  @After
  public void tearDown() {
    CurrentNexusOperationContext.unset();
  }

  @Test
  public void nexusMetadataAddsCallbackLinksAndRequestId() {
    Map<String, String> callbackHeaders = new HashMap<>();
    callbackHeaders.put("Custom-Header", "value");
    NexusOperationMetadata metadata =
        new NexusOperationMetadata(
            "nexus-request-id", "http://localhost/callback", callbackHeaders);
    nexusContext.setNexusOperationMetadata(metadata);
    Link link = workflowEventLink();
    nexusContext.setRequestLinks(Collections.singletonList(link));

    invoker.startActivity(newStartActivityInput());

    ArgumentCaptor<StartActivityExecutionRequest> captor =
        ArgumentCaptor.forClass(StartActivityExecutionRequest.class);
    verify(genericClient).startActivity(captor.capture());
    StartActivityExecutionRequest request = captor.getValue();
    Assert.assertEquals("nexus-request-id", request.getRequestId());
    Assert.assertEquals(Collections.singletonList(link), request.getLinksList());
    Assert.assertEquals(1, request.getCompletionCallbacksCount());
    Assert.assertEquals(
        "http://localhost/callback", request.getCompletionCallbacks(0).getNexus().getUrl());
    Assert.assertEquals(
        "value", request.getCompletionCallbacks(0).getNexus().getHeaderOrThrow("custom-header"));
    Assert.assertNotNull(metadata.operationToken);
    Assert.assertEquals(
        metadata.operationToken,
        request
            .getCompletionCallbacks(0)
            .getNexus()
            .getHeaderOrThrow(io.nexusrpc.Header.OPERATION_TOKEN.toLowerCase()));
  }

  @Test
  public void nexusContextWithoutMetadataStartsOrdinaryActivity() {
    nexusContext.setRequestLinks(Collections.singletonList(workflowEventLink()));

    invoker.startActivity(newStartActivityInput());

    ArgumentCaptor<StartActivityExecutionRequest> captor =
        ArgumentCaptor.forClass(StartActivityExecutionRequest.class);
    verify(genericClient).startActivity(captor.capture());
    StartActivityExecutionRequest request = captor.getValue();
    Assert.assertFalse(request.getRequestId().isEmpty());
    Assert.assertEquals(0, request.getLinksCount());
    Assert.assertEquals(0, request.getCompletionCallbacksCount());
  }

  private static StartActivityInput newStartActivityInput() {
    StartActivityOptions options =
        StartActivityOptions.newBuilder()
            .setId("activity-id")
            .setTaskQueue("test-task-queue")
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .build();
    return new StartActivityInput("TestActivity", Collections.emptyList(), options, Header.empty());
  }

  private static Link workflowEventLink() {
    return Link.newBuilder()
        .setWorkflowEvent(
            Link.WorkflowEvent.newBuilder()
                .setNamespace(NAMESPACE)
                .setWorkflowId("caller-workflow-id")
                .setRunId("caller-run-id")
                .setEventRef(
                    Link.WorkflowEvent.EventReference.newBuilder()
                        .setEventType(EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED)))
        .build();
  }
}
