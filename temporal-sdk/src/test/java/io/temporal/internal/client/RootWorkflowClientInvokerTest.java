package io.temporal.internal.client;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.uber.m3.tally.NoopScope;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionResponse;
import io.temporal.api.workflowservice.v1.SignalWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.SignalWorkflowExecutionResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.interceptors.Header;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor.WorkflowSignalInput;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor.WorkflowSignalWithStartInput;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor.WorkflowStartInput;
import io.temporal.internal.client.external.GenericWorkflowClient;
import io.temporal.internal.nexus.CurrentNexusOperationContext;
import io.temporal.internal.nexus.InternalNexusOperationContext;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for signal-class request-ID derivation by {@link RootWorkflowClientInvoker}, in
 * particular the redelivery-safety / collision-avoidance behavior of {@code signal()} and {@code
 * signalWithStart()} when issued from inside a Nexus operation handler.
 */
public class RootWorkflowClientInvokerTest {

  private static final String NAMESPACE = "test-namespace";

  private GenericWorkflowClient genericClient;
  private RootWorkflowClientInvoker invoker;
  private InternalNexusOperationContext nexusContext;

  @Before
  public void setUp() {
    genericClient = mock(GenericWorkflowClient.class);
    when(genericClient.signal(any(SignalWorkflowExecutionRequest.class)))
        .thenReturn(SignalWorkflowExecutionResponse.newBuilder().build());
    when(genericClient.signalWithStart(any(SignalWithStartWorkflowExecutionRequest.class)))
        .thenReturn(
            SignalWithStartWorkflowExecutionResponse.newBuilder().setRunId("run-id").build());
    invoker =
        new RootWorkflowClientInvoker(
            genericClient,
            WorkflowClientOptions.newBuilder()
                .setNamespace(NAMESPACE)
                .setIdentity("test-identity")
                .validateAndBuildWithDefaults(),
            new WorkerFactoryRegistry());
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
  public void signalInNexusContextDerivesFromAmbientRequestIdRatherThanReusingItVerbatim() {
    nexusContext.setRequestId("ambient-nexus-request-id");

    invoker.signal(newSignalInput());

    ArgumentCaptor<SignalWorkflowExecutionRequest> captor =
        ArgumentCaptor.forClass(SignalWorkflowExecutionRequest.class);
    verify(genericClient).signal(captor.capture());
    String requestId = captor.getValue().getRequestId();
    Assert.assertFalse(requestId.isEmpty());
    Assert.assertNotEquals("ambient-nexus-request-id", requestId);
    Assert.assertTrue(requestId.startsWith("ambient-nexus-request-id"));
  }

  @Test
  public void twoSignalsInSameInvocationGetDistinctRequestIds() {
    nexusContext.setRequestId("ambient-nexus-request-id");

    invoker.signal(newSignalInput());
    invoker.signal(newSignalInput());

    ArgumentCaptor<SignalWorkflowExecutionRequest> captor =
        ArgumentCaptor.forClass(SignalWorkflowExecutionRequest.class);
    verify(genericClient, org.mockito.Mockito.times(2)).signal(captor.capture());
    String firstRequestId = captor.getAllValues().get(0).getRequestId();
    String secondRequestId = captor.getAllValues().get(1).getRequestId();
    Assert.assertNotEquals(firstRequestId, secondRequestId);
  }

  @Test
  public void signalThenSignalWithStartInSameInvocationGetDistinctRequestIds() {
    nexusContext.setRequestId("ambient-nexus-request-id");

    invoker.signal(newSignalInput());
    invoker.signalWithStart(newSignalWithStartInput());

    ArgumentCaptor<SignalWorkflowExecutionRequest> signalCaptor =
        ArgumentCaptor.forClass(SignalWorkflowExecutionRequest.class);
    verify(genericClient).signal(signalCaptor.capture());
    ArgumentCaptor<SignalWithStartWorkflowExecutionRequest> signalWithStartCaptor =
        ArgumentCaptor.forClass(SignalWithStartWorkflowExecutionRequest.class);
    verify(genericClient).signalWithStart(signalWithStartCaptor.capture());

    Assert.assertNotEquals(
        signalCaptor.getValue().getRequestId(), signalWithStartCaptor.getValue().getRequestId());
  }

  @Test
  public void sameSequenceOfCallsOnRedeliveredContextYieldsSameRequestIds() {
    // Simulate two redelivery attempts of the same Nexus task: NexusTaskHandlerImpl.handle()
    // creates a fresh InternalNexusOperationContext per attempt, but the server redelivers the
    // same task, so both attempts get the same ambient requestId.
    InternalNexusOperationContext attempt1 =
        new InternalNexusOperationContext(
            NAMESPACE, "tq", "endpoint", new NoopScope(), mock(WorkflowClient.class));
    attempt1.setRequestId("redelivered-request-id");
    InternalNexusOperationContext attempt2 =
        new InternalNexusOperationContext(
            NAMESPACE, "tq", "endpoint", new NoopScope(), mock(WorkflowClient.class));
    attempt2.setRequestId("redelivered-request-id");

    // Simulate the handler issuing the same two signal-class calls, in the same order, on each
    // attempt.
    String attempt1First = attempt1.nextSignalRequestId();
    String attempt1Second = attempt1.nextSignalRequestId();
    String attempt2First = attempt2.nextSignalRequestId();
    String attempt2Second = attempt2.nextSignalRequestId();

    Assert.assertEquals(attempt1First, attempt2First);
    Assert.assertEquals(attempt1Second, attempt2Second);
    Assert.assertNotEquals(attempt1First, attempt1Second);
  }

  @Test
  public void signalOutsideNexusContextFallsBackToFreshRandomRequestIdEachCall() {
    CurrentNexusOperationContext.unset();

    invoker.signal(newSignalInput());
    invoker.signal(newSignalInput());

    ArgumentCaptor<SignalWorkflowExecutionRequest> captor =
        ArgumentCaptor.forClass(SignalWorkflowExecutionRequest.class);
    verify(genericClient, org.mockito.Mockito.times(2)).signal(captor.capture());
    String firstRequestId = captor.getAllValues().get(0).getRequestId();
    String secondRequestId = captor.getAllValues().get(1).getRequestId();
    Assert.assertFalse(firstRequestId.isEmpty());
    Assert.assertFalse(secondRequestId.isEmpty());
    Assert.assertNotEquals(firstRequestId, secondRequestId);
  }

  @Test
  public void signalInNexusContextWithoutAmbientRequestIdFallsBackToFreshRandomRequestId() {
    // Nexus context is set (e.g. inside an operation handler), but no ambient requestId was ever
    // populated (bare context not populated by NexusTaskHandlerImpl).
    invoker.signal(newSignalInput());

    ArgumentCaptor<SignalWorkflowExecutionRequest> captor =
        ArgumentCaptor.forClass(SignalWorkflowExecutionRequest.class);
    verify(genericClient).signal(captor.capture());
    Assert.assertFalse(captor.getValue().getRequestId().isEmpty());
  }

  @Test
  public void
      signalWithStartInNexusContextDerivesFromAmbientRequestIdRatherThanReusingItVerbatim() {
    nexusContext.setRequestId("ambient-nexus-request-id");

    invoker.signalWithStart(newSignalWithStartInput());

    ArgumentCaptor<SignalWithStartWorkflowExecutionRequest> captor =
        ArgumentCaptor.forClass(SignalWithStartWorkflowExecutionRequest.class);
    verify(genericClient).signalWithStart(captor.capture());
    String requestId = captor.getValue().getRequestId();
    Assert.assertFalse(requestId.isEmpty());
    Assert.assertNotEquals("ambient-nexus-request-id", requestId);
    Assert.assertTrue(requestId.startsWith("ambient-nexus-request-id"));
  }

  @Test
  public void twoSignalWithStartsInSameInvocationGetDistinctRequestIds() {
    nexusContext.setRequestId("ambient-nexus-request-id");

    invoker.signalWithStart(newSignalWithStartInput());
    invoker.signalWithStart(newSignalWithStartInput());

    ArgumentCaptor<SignalWithStartWorkflowExecutionRequest> captor =
        ArgumentCaptor.forClass(SignalWithStartWorkflowExecutionRequest.class);
    verify(genericClient, org.mockito.Mockito.times(2)).signalWithStart(captor.capture());
    String firstRequestId = captor.getAllValues().get(0).getRequestId();
    String secondRequestId = captor.getAllValues().get(1).getRequestId();
    Assert.assertNotEquals(firstRequestId, secondRequestId);
  }

  @Test
  public void signalWithStartOutsideNexusContextFallsBackToFreshRandomRequestIdEachCall() {
    CurrentNexusOperationContext.unset();

    invoker.signalWithStart(newSignalWithStartInput());
    invoker.signalWithStart(newSignalWithStartInput());

    ArgumentCaptor<SignalWithStartWorkflowExecutionRequest> captor =
        ArgumentCaptor.forClass(SignalWithStartWorkflowExecutionRequest.class);
    verify(genericClient, org.mockito.Mockito.times(2)).signalWithStart(captor.capture());
    String firstRequestId = captor.getAllValues().get(0).getRequestId();
    String secondRequestId = captor.getAllValues().get(1).getRequestId();
    Assert.assertFalse(firstRequestId.isEmpty());
    Assert.assertFalse(secondRequestId.isEmpty());
    Assert.assertNotEquals(firstRequestId, secondRequestId);
  }

  private static WorkflowSignalInput newSignalInput() {
    return new WorkflowSignalInput(
        WorkflowExecution.newBuilder().setWorkflowId("callee-workflow-id").build(),
        "test-signal",
        Header.empty(),
        new Object[0]);
  }

  private static WorkflowSignalWithStartInput newSignalWithStartInput() {
    WorkflowStartInput startInput =
        new WorkflowStartInput(
            "callee-workflow-id",
            "TestWorkflow",
            Header.empty(),
            new Object[0],
            WorkflowOptions.newBuilder().build());
    return new WorkflowSignalWithStartInput(startInput, "test-signal", new Object[0]);
  }
}
