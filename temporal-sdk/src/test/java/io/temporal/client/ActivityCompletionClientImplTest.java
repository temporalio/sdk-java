package io.temporal.client;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.uber.m3.tally.NoopScope;
import com.uber.m3.tally.Scope;
import io.temporal.activity.ManualActivityCompletionClient;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.internal.client.external.ManualActivityCompletionClientFactory;
import io.temporal.workflow.Functions;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

public class ActivityCompletionClientImplTest {

  private ManualActivityCompletionClientFactory factory;
  private ManualActivityCompletionClient manualClient;
  private Functions.Proc completionHandle;
  private Scope scope;
  private ActivityCompletionClientImpl client;

  @Before
  public void setUp() {
    factory = mock(ManualActivityCompletionClientFactory.class);
    manualClient = mock(ManualActivityCompletionClient.class);
    completionHandle = mock(Functions.Proc.class);
    scope = new NoopScope();
    when(factory.getClient(any(WorkflowExecution.class), any(), any(), any()))
        .thenReturn(manualClient);
    client = new ActivityCompletionClientImpl(factory, completionHandle, scope, null);
  }

  // complete(String activityId, Optional<String> activityRunId, R result)

  @Test
  public void testCompleteStandaloneWithRunId() {
    client.completeStandalone("my-act", Optional.of("run-123"), "result");

    WorkflowExecution expected =
        WorkflowExecution.newBuilder().setWorkflowId("").setRunId("run-123").build();
    verify(factory).getClient(eq(expected), eq("my-act"), eq(scope), any());
    verify(manualClient).complete("result");
    verify(completionHandle).apply();
  }

  @Test
  public void testCompleteStandaloneWithoutRunId() {
    client.completeStandalone("my-act", Optional.empty(), "result");

    WorkflowExecution expected =
        WorkflowExecution.newBuilder().setWorkflowId("").setRunId("").build();
    verify(factory).getClient(eq(expected), eq("my-act"), eq(scope), any());
    verify(manualClient).complete("result");
    verify(completionHandle).apply();
  }

  @Test
  public void testCompleteStandaloneCallsCompletionHandleOnException() {
    doThrow(new RuntimeException("boom")).when(manualClient).complete(any());

    try {
      client.completeStandalone("my-act", Optional.empty(), "result");
    } catch (RuntimeException ignored) {
    }

    verify(completionHandle).apply();
  }

  // completeExceptionally(String activityId, Optional<String> activityRunId, Exception result)

  @Test
  public void testCompleteExceptionallyStandaloneWithRunId() {
    Exception ex = new RuntimeException("fail");
    client.completeExceptionallyStandalone("my-act", Optional.of("run-456"), ex);

    WorkflowExecution expected =
        WorkflowExecution.newBuilder().setWorkflowId("").setRunId("run-456").build();
    verify(factory).getClient(eq(expected), eq("my-act"), eq(scope), any());
    verify(manualClient).fail(ex);
    verify(completionHandle).apply();
  }

  @Test
  public void testCompleteExceptionallyStandaloneWithoutRunId() {
    Exception ex = new RuntimeException("fail");
    client.completeExceptionallyStandalone("my-act", Optional.empty(), ex);

    WorkflowExecution expected =
        WorkflowExecution.newBuilder().setWorkflowId("").setRunId("").build();
    verify(factory).getClient(eq(expected), eq("my-act"), eq(scope), any());
    verify(manualClient).fail(ex);
    verify(completionHandle).apply();
  }

  @Test
  public void testCompleteExceptionallyStandaloneCallsCompletionHandleOnException() {
    doThrow(new RuntimeException("boom")).when(manualClient).fail(any());

    try {
      client.completeExceptionallyStandalone("my-act", Optional.empty(), new Exception("ex"));
    } catch (RuntimeException ignored) {
    }

    verify(completionHandle).apply();
  }

  // reportCancellation(String activityId, Optional<String> activityRunId, V details)

  @Test
  public void testReportCancellationStandaloneWithRunId() {
    client.reportCancellationStandalone("my-act", Optional.of("run-789"), "details");

    WorkflowExecution expected =
        WorkflowExecution.newBuilder().setWorkflowId("").setRunId("run-789").build();
    verify(factory).getClient(eq(expected), eq("my-act"), eq(scope), any());
    verify(manualClient).reportCancellation("details");
    verify(completionHandle).apply();
  }

  @Test
  public void testReportCancellationStandaloneWithoutRunId() {
    client.reportCancellationStandalone("my-act", Optional.empty(), null);

    WorkflowExecution expected =
        WorkflowExecution.newBuilder().setWorkflowId("").setRunId("").build();
    verify(factory).getClient(eq(expected), eq("my-act"), eq(scope), any());
    verify(manualClient).reportCancellation(null);
    verify(completionHandle).apply();
  }

  @Test
  public void testReportCancellationStandaloneCallsCompletionHandleOnException() {
    doThrow(new RuntimeException("boom")).when(manualClient).reportCancellation(any());

    try {
      client.reportCancellationStandalone("my-act", Optional.empty(), null);
    } catch (RuntimeException ignored) {
    }

    verify(completionHandle).apply();
  }

  // heartbeat(String activityId, Optional<String> activityRunId, V details)

  @Test
  public void testHeartbeatStandaloneWithRunId() throws ActivityCompletionException {
    client.heartbeatStandalone("my-act", Optional.of("run-abc"), "ping");

    WorkflowExecution expected =
        WorkflowExecution.newBuilder().setWorkflowId("").setRunId("run-abc").build();
    verify(factory).getClient(eq(expected), eq("my-act"), eq(scope), any());
    verify(manualClient).recordHeartbeat("ping");
  }

  @Test
  public void testHeartbeatStandaloneWithoutRunId() throws ActivityCompletionException {
    client.heartbeatStandalone("my-act", Optional.empty(), null);

    WorkflowExecution expected =
        WorkflowExecution.newBuilder().setWorkflowId("").setRunId("").build();
    verify(factory).getClient(eq(expected), eq("my-act"), eq(scope), any());
    verify(manualClient).recordHeartbeat(null);
  }

  @Test
  public void testHeartbeatStandaloneDoesNotCallCompletionHandle()
      throws ActivityCompletionException {
    client.heartbeatStandalone("my-act", Optional.empty(), null);
    verify(completionHandle, never()).apply();
  }
}
