package io.temporal.internal.sync;

import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.nexus.v1.PropagatedSerializationContext;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowUpdateException;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.failure.ApplicationFailure;
import io.temporal.internal.nexus.ContextRecordingCodec;
import io.temporal.payload.context.NexusSerializationContext;
import io.temporal.payload.context.WorkflowSerializationContext;
import io.temporal.testing.CloudTestExclusion.RequiresLocalServer;
import io.temporal.testing.CloudTestExclusionNote;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.Arrays;
import java.util.Collections;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Tests that a workflow started by a Nexus operation handler encodes its result and closing
 * failure, which are returned to the Nexus caller, with the Nexus context the server propagates in
 * the WorkflowExecutionStarted event, and keeps the workflow context for everything else.
 *
 * <p>The test server doesn't propagate the context, so each test runs the workflow, adds the
 * context to the started event of the recorded history, and replays it.
 */
@CloudTestExclusionNote("This test directly creates and controls a local test service.")
@Category(RequiresLocalServer.class)
public class WorkflowNexusSerializationContextReplayTest {

  private static final String TASK_QUEUE = "nexus-serialization-context-replay";
  private static final String NAMESPACE = "default";
  private static final NexusSerializationContext NEXUS_CONTEXT =
      new NexusSerializationContext("endpoint", "service", "operation");
  private static final PropagatedSerializationContext PROPAGATED =
      PropagatedSerializationContext.newBuilder()
          .setEndpoint("endpoint")
          .setService("service")
          .setOperation("operation")
          .build();
  private static final String NEXUS = ContextRecordingCodec.describe(NEXUS_CONTEXT);

  @WorkflowInterface
  public interface EchoWorkflow {
    @WorkflowMethod
    String execute(String input);

    @UpdateMethod
    void reject();
  }

  public static class EchoWorkflowImpl implements EchoWorkflow {
    private boolean rejected;

    @Override
    public String execute(String input) {
      if ("update".equals(input)) {
        Workflow.await(() -> rejected);
      }
      if ("fail".equals(input)) {
        throw ApplicationFailure.newNonRetryableFailure("failed", "TestFailure", "detail");
      }
      if ("memo".equals(input)) {
        Workflow.upsertMemo(Collections.singletonMap("upserted", input));
        return (String) Workflow.getMemo("upserted", String.class);
      }
      if ("can".equals(input)) {
        Workflow.continueAsNew("continued");
      }
      return input;
    }

    @Override
    public void reject() {
      rejected = true;
      throw ApplicationFailure.newNonRetryableFailure("rejected", "TestFailure", "detail");
    }
  }

  private ContextRecordingCodec codec;
  private TestWorkflowEnvironment testEnvironment;
  private Worker worker;

  @Before
  public void setUp() {
    codec = new ContextRecordingCodec();
    testEnvironment =
        TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder()
                // With time skipping, the update in updateFailureKeepsWorkflowContext isn't
                // delivered to the blocked workflow for several minutes.
                .setUseTimeskipping(false)
                .setWorkflowClientOptions(
                    WorkflowClientOptions.newBuilder()
                        .setNamespace(NAMESPACE)
                        .setDataConverter(codec.dataConverter())
                        .build())
                .build());
    worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(EchoWorkflowImpl.class);
    testEnvironment.start();
  }

  @After
  public void tearDown() {
    testEnvironment.close();
  }

  @Test
  public void propagatedContextIsUsedOnlyForResult() throws Exception {
    WorkflowExecutionHistory history = run("hello");
    codec.reset();

    worker.replayWorkflowExecution(withPropagatedContext(history));

    // The input keeps the workflow context; the result is returned to the Nexus caller.
    Assert.assertEquals(
        Collections.singletonList(workflowContext("hello")), codec.decodeContexts());
    Assert.assertEquals(Collections.singletonList(NEXUS), codec.encodeContexts());
  }

  @Test
  public void propagatedContextIsUsedForClosingFailure() throws Exception {
    WorkflowExecutionHistory history = run("fail");
    codec.reset();

    worker.replayWorkflowExecution(withPropagatedContext(history));

    Assert.assertTrue(
        "failure should be encoded with the Nexus context, saw " + codec.encodeContexts(),
        codec.encodeContexts().contains(NEXUS));
    Assert.assertFalse(codec.encodeContexts().contains(workflowContext("fail")));
  }

  @Test
  public void withoutPropagatedContextWorkflowContextIsUsed() throws Exception {
    WorkflowExecutionHistory history = run("hello");
    codec.reset();

    worker.replayWorkflowExecution(history);

    Assert.assertFalse(codec.decodeContexts().contains(NEXUS));
    Assert.assertFalse(codec.encodeContexts().contains(NEXUS));
    Assert.assertTrue(codec.encodeContexts().contains(workflowContext("hello")));
  }

  @Test
  public void memoKeepsWorkflowContext() throws Exception {
    WorkflowExecutionHistory history = run("memo");
    codec.reset();

    worker.replayWorkflowExecution(withPropagatedContext(history));

    // Upserting encodes the memo, and reading it back decodes it; only the result uses the Nexus
    // context.
    Assert.assertEquals(Arrays.asList(workflowContext("memo"), NEXUS), codec.encodeContexts());
    Assert.assertFalse(codec.decodeContexts().contains(NEXUS));
  }

  @Test
  public void continueAsNewInputKeepsWorkflowContext() throws Exception {
    WorkflowExecutionHistory history = run("can");
    codec.reset();

    worker.replayWorkflowExecution(withPropagatedContext(history));

    // The continued run decodes its input with the workflow context.
    Assert.assertEquals(Collections.singletonList(workflowContext("can")), codec.encodeContexts());
  }

  @Test
  public void updateFailureKeepsWorkflowContext() throws Exception {
    WorkflowClient client = testEnvironment.getWorkflowClient();
    EchoWorkflow workflow =
        client.newWorkflowStub(
            EchoWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(TASK_QUEUE)
                .setWorkflowId(workflowId("update"))
                .build());
    WorkflowClient.start(workflow::execute, "update");
    Assert.assertThrows(WorkflowUpdateException.class, workflow::reject);
    WorkflowStub.fromTyped(workflow).getResult(String.class);
    WorkflowExecutionHistory history =
        client.fetchHistory(
            workflowId("update"), WorkflowStub.fromTyped(workflow).getExecution().getRunId());
    codec.reset();

    worker.replayWorkflowExecution(withPropagatedContext(history));

    // The update failure belongs to the update, so it keeps the workflow context even though the
    // workflow's result uses the Nexus context.
    Assert.assertEquals(Arrays.asList(workflowContext("update"), NEXUS), codec.encodeContexts());
  }

  /** Runs the workflow to completion and returns the history of its first run. */
  private WorkflowExecutionHistory run(String input) {
    WorkflowClient client = testEnvironment.getWorkflowClient();
    String workflowId = workflowId(input);
    EchoWorkflow workflow =
        client.newWorkflowStub(
            EchoWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(TASK_QUEUE)
                .setWorkflowId(workflowId)
                .build());
    try {
      workflow.execute(input);
    } catch (WorkflowFailedException e) {
      // Expected for the failing workflow.
    }
    return client.fetchHistory(
        workflowId, WorkflowStub.fromTyped(workflow).getExecution().getRunId());
  }

  /** Returns the history with the Nexus context added to its WorkflowExecutionStarted event. */
  private static WorkflowExecutionHistory withPropagatedContext(WorkflowExecutionHistory history) {
    History.Builder builder = History.newBuilder().addAllEvents(history.getEvents());
    HistoryEvent.Builder started = builder.getEventsBuilder(0);
    started
        .getWorkflowExecutionStartedEventAttributesBuilder()
        .setPropagatedNexusSerializationContext(PROPAGATED);
    return new WorkflowExecutionHistory(
        builder.build(), history.getWorkflowExecution().getWorkflowId());
  }

  private static String workflowId(String input) {
    return "nexus-serialization-context-" + input;
  }

  private static String workflowContext(String input) {
    return ContextRecordingCodec.describe(
        new WorkflowSerializationContext(NAMESPACE, workflowId(input)));
  }
}
