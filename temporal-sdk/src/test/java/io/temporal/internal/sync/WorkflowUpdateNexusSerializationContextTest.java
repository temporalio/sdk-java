package io.temporal.internal.sync;

import static org.mockito.Mockito.mock;

import com.uber.m3.tally.NoopScope;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowUpdateException;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.failure.ApplicationFailure;
import io.temporal.internal.nexus.ContextRecordingCodec;
import io.temporal.internal.nexus.CurrentNexusOperationContext;
import io.temporal.internal.nexus.InternalNexusOperationContext;
import io.temporal.payload.context.NexusSerializationContext;
import io.temporal.payload.context.WorkflowSerializationContext;
import io.temporal.testing.CloudTestExclusion.RequiresLocalServer;
import io.temporal.testing.CloudTestExclusionNote;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.UpdateValidatorMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.Arrays;
import java.util.UUID;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Tests that the worker encodes the result, failure and rejection of an update sent by a Nexus
 * operation handler, which are returned to the Nexus caller, with the Nexus context carried on the
 * update request, and keeps the workflow context for the update's arguments.
 *
 * <p>The test server passes the update request, including the context, through to the worker, so
 * each test sends the update from inside a Nexus operation context on the test thread.
 */
@CloudTestExclusionNote("This test directly creates and controls a local test service.")
@Category(RequiresLocalServer.class)
public class WorkflowUpdateNexusSerializationContextTest {

  private static final String TASK_QUEUE = "nexus-serialization-context-update";
  private static final String NAMESPACE = "default";
  private static final NexusSerializationContext NEXUS_CONTEXT =
      new NexusSerializationContext("endpoint", "service", "operation");
  private static final String NEXUS = ContextRecordingCodec.describe(NEXUS_CONTEXT);

  @WorkflowInterface
  public interface UpdateTargetWorkflow {
    @WorkflowMethod
    void execute();

    @UpdateMethod
    String echo(String input);

    @UpdateValidatorMethod(updateName = "echo")
    void validateEcho(String input);

    @UpdateMethod
    void finish();
  }

  public static class UpdateTargetWorkflowImpl implements UpdateTargetWorkflow {
    private boolean finished;

    @Override
    public void execute() {
      Workflow.await(() -> finished);
    }

    @Override
    public String echo(String input) {
      if ("fail".equals(input)) {
        throw ApplicationFailure.newNonRetryableFailure("failed", "TestFailure", "detail");
      }
      return input;
    }

    @Override
    public void validateEcho(String input) {
      if ("reject".equals(input)) {
        throw ApplicationFailure.newNonRetryableFailure("rejected", "TestFailure", "detail");
      }
    }

    @Override
    public void finish() {
      finished = true;
    }
  }

  private ContextRecordingCodec codec;
  private TestWorkflowEnvironment testEnvironment;
  private Worker worker;
  private UpdateTargetWorkflow workflow;
  private String workflowId;

  @Before
  public void setUp() {
    codec = new ContextRecordingCodec();
    testEnvironment =
        TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder()
                // With time skipping, updates aren't delivered to the blocked workflow for several
                // minutes.
                .setUseTimeskipping(false)
                .setWorkflowClientOptions(
                    WorkflowClientOptions.newBuilder()
                        .setNamespace(NAMESPACE)
                        .setDataConverter(codec.dataConverter())
                        .build())
                .build());
    worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(UpdateTargetWorkflowImpl.class);
    testEnvironment.start();

    workflowId = "nexus-serialization-context-update-" + UUID.randomUUID();
    workflow =
        testEnvironment
            .getWorkflowClient()
            .newWorkflowStub(
                UpdateTargetWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(TASK_QUEUE)
                    .setWorkflowId(workflowId)
                    .build());
    WorkflowClient.start(workflow::execute);
  }

  @After
  public void tearDown() {
    CurrentNexusOperationContext.unset();
    testEnvironment.close();
  }

  @Test
  public void updateSentByNexusHandlerUsesNexusContextForResult() throws Exception {
    codec.reset();
    Assert.assertEquals("hello", sendFromNexusHandler("hello"));

    // The client encodes the argument and the worker decodes it with the workflow context; the
    // worker encodes the result with the Nexus context.
    Assert.assertEquals(Arrays.asList(workflowContext(), NEXUS), codec.encodeContexts());
    Assert.assertEquals(workflowContext(), codec.decodeContexts().get(0));

    // Replaying re-encodes the result with the context from the update's accepted event. The
    // finish update wasn't sent by a Nexus handler, so its result keeps the workflow context.
    WorkflowExecutionHistory history = finishAndFetchHistory();
    codec.reset();
    worker.replayWorkflowExecution(history);
    Assert.assertEquals(Arrays.asList(NEXUS, workflowContext()), codec.encodeContexts());
  }

  @Test
  public void updateSentByNexusHandlerUsesNexusContextForFailure() throws Exception {
    codec.reset();
    Assert.assertThrows(WorkflowUpdateException.class, () -> sendFromNexusHandler("fail"));

    Assert.assertEquals(Arrays.asList(workflowContext(), NEXUS), codec.encodeContexts());

    WorkflowExecutionHistory history = finishAndFetchHistory();
    codec.reset();
    worker.replayWorkflowExecution(history);
    Assert.assertEquals(Arrays.asList(NEXUS, workflowContext()), codec.encodeContexts());
  }

  @Test
  public void updateSentByNexusHandlerUsesNexusContextForRejection() {
    codec.reset();
    Assert.assertThrows(WorkflowUpdateException.class, () -> sendFromNexusHandler("reject"));

    Assert.assertEquals(Arrays.asList(workflowContext(), NEXUS), codec.encodeContexts());
  }

  @Test
  public void updateNotSentByNexusHandlerUsesWorkflowContext() {
    codec.reset();
    Assert.assertEquals("hello", workflow.echo("hello"));
    Assert.assertThrows(WorkflowUpdateException.class, () -> workflow.echo("fail"));
    Assert.assertThrows(WorkflowUpdateException.class, () -> workflow.echo("reject"));

    Assert.assertFalse(codec.encodeContexts().contains(NEXUS));
  }

  /** Sends the echo update from inside a Nexus operation handler. */
  private String sendFromNexusHandler(String input) {
    InternalNexusOperationContext nexusContext =
        new InternalNexusOperationContext(
            NAMESPACE, TASK_QUEUE, "endpoint", new NoopScope(), mock(WorkflowClient.class));
    nexusContext.setSerializationContext(NEXUS_CONTEXT);
    CurrentNexusOperationContext.set(nexusContext);
    try {
      return workflow.echo(input);
    } finally {
      CurrentNexusOperationContext.unset();
    }
  }

  private WorkflowExecutionHistory finishAndFetchHistory() {
    workflow.finish();
    WorkflowStub stub = WorkflowStub.fromTyped(workflow);
    stub.getResult(Void.class);
    return testEnvironment
        .getWorkflowClient()
        .fetchHistory(workflowId, stub.getExecution().getRunId());
  }

  private String workflowContext() {
    return ContextRecordingCodec.describe(new WorkflowSerializationContext(NAMESPACE, workflowId));
  }
}
