package io.temporal.workflow.signalTests;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.testing.internal.TracingWorkerInterceptor;
import io.temporal.workflow.Async;
import io.temporal.workflow.CompletablePromise;
import io.temporal.workflow.ExternalWorkflowStub;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows.TestSignaledWorkflow;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow2;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class SignalExternalWorkflowTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestSignalExternalWorkflow.class, SignalingChildImpl.class)
          .build();

  @Test
  public void testSignalExternalWorkflow() {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(2000))
            .setWorkflowTaskTimeout(Duration.ofSeconds(60))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();
    TestSignaledWorkflow client =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestSignaledWorkflow.class, options);
    Assert.assertEquals("Hello World!", client.execute());
    WorkflowStub stub = WorkflowStub.fromTyped(client);
    testWorkflowRule
        .getInterceptor(TracingWorkerInterceptor.class)
        .setExpected(
            "interceptExecuteWorkflow " + stub.getExecution().getWorkflowId(),
            "registerSignalHandlers testSignal",
            "newThread workflow-method",
            "executeChildWorkflow TestWorkflow2",
            "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP, // child
            "newThread workflow-method",
            "signalExternalWorkflow " + SDKTestWorkflowRule.UUID_REGEXP + " testSignal",
            "handleSignal testSignal");
  }

  public static class TestSignalExternalWorkflow implements TestSignaledWorkflow {

    private final TestWorkflow2 child = Workflow.newChildWorkflowStub(TestWorkflow2.class);

    private final CompletablePromise<Object> fromSignal = Workflow.newPromise();

    @Override
    public String execute() {
      Promise<String> result =
          Async.function(child::execute, "Hello", Workflow.getInfo().getWorkflowId());
      return result.get() + " " + fromSignal.get() + "!";
    }

    @Override
    public void signal(String arg) {
      fromSignal.complete(arg);
    }
  }

  public static class SignalingChildImpl implements TestWorkflow2 {

    @Override
    public String execute(String greeting, String parentWorkflowId) {
      WorkflowExecution parentExecution =
          WorkflowExecution.newBuilder().setWorkflowId(parentWorkflowId).build();
      TestSignaledWorkflow parent =
          Workflow.newExternalWorkflowStub(TestSignaledWorkflow.class, parentExecution);
      ExternalWorkflowStub untyped = ExternalWorkflowStub.fromTyped(parent);
      //  Same as parent.signal1("World");
      untyped.signal("testSignal", "World");
      return greeting;
    }
  }
}
