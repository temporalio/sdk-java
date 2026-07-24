package io.temporal.internal.client;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.temporal.api.common.v1.Priority;
import io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionResponse;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.interceptors.Header;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor.WorkflowSignalWithStartInput;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor.WorkflowStartInput;
import io.temporal.internal.client.external.GenericWorkflowClient;
import io.temporal.internal.common.ProtoConverters;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit test guarding that {@link RootWorkflowClientInvoker#signalWithStart} propagates the {@link
 * io.temporal.common.Priority} set on {@link WorkflowOptions} onto the outgoing {@link
 * SignalWithStartWorkflowExecutionRequest}. A plain {@code start} already carries priority, so this
 * asserts the signalWithStart path stays in sync. Priority also carries the fairness fields, so a
 * regression here silently drops fairness as well.
 */
public class RootWorkflowClientInvokerPriorityPropagationTest {

  private static final String NAMESPACE = "test-namespace";
  private static final String WORKFLOW_ID = "wf-target";

  private GenericWorkflowClient genericClient;
  private RootWorkflowClientInvoker invoker;

  @Before
  public void setUp() {
    genericClient = mock(GenericWorkflowClient.class);
    invoker =
        new RootWorkflowClientInvoker(
            genericClient,
            WorkflowClientOptions.newBuilder()
                .setNamespace(NAMESPACE)
                .validateAndBuildWithDefaults(),
            new WorkerFactoryRegistry());
  }

  @Test
  public void signalWithStartPropagatesPriorityAndFairness() {
    io.temporal.common.Priority priority =
        io.temporal.common.Priority.newBuilder()
            .setPriorityKey(5)
            .setFairnessKey("tenant-123")
            .setFairnessWeight(2.5f)
            .build();
    WorkflowOptions options =
        WorkflowOptions.newBuilder().setTaskQueue("tq").setPriority(priority).build();

    when(genericClient.signalWithStart(any(SignalWithStartWorkflowExecutionRequest.class)))
        .thenReturn(
            SignalWithStartWorkflowExecutionResponse.newBuilder().setRunId("target-run").build());

    invoker.signalWithStart(newSignalWithStartInput(options));

    ArgumentCaptor<SignalWithStartWorkflowExecutionRequest> captor =
        ArgumentCaptor.forClass(SignalWithStartWorkflowExecutionRequest.class);
    org.mockito.Mockito.verify(genericClient).signalWithStart(captor.capture());
    SignalWithStartWorkflowExecutionRequest sent = captor.getValue();

    Assert.assertTrue("request should carry priority", sent.hasPriority());
    Priority expected = ProtoConverters.toProto(priority);
    Assert.assertEquals(expected, sent.getPriority());
    Assert.assertEquals(5, sent.getPriority().getPriorityKey());
    Assert.assertEquals("tenant-123", sent.getPriority().getFairnessKey());
    Assert.assertEquals(2.5f, sent.getPriority().getFairnessWeight(), 0.0f);
  }

  private static WorkflowSignalWithStartInput newSignalWithStartInput(WorkflowOptions options) {
    WorkflowStartInput startInput =
        new WorkflowStartInput(
            WORKFLOW_ID, "TestWorkflow", Header.empty(), new Object[] {}, options);
    return new WorkflowSignalWithStartInput(
        startInput, "test-signal", new Object[] {"signal-payload"});
  }
}
