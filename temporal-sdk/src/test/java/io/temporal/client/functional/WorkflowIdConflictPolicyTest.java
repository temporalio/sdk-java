package io.temporal.client.functional;

import static org.junit.Assert.*;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.client.*;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows;
import java.util.Optional;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowIdConflictPolicyTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestWorkflowImpl2.class).build();

  @Test
  public void policyTerminateExisting() {
    String workflowId = UUID.randomUUID().toString();

    WorkflowOptions.Builder workflowOptionsBuilder =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowIdConflictPolicy(
                WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_TERMINATE_EXISTING)
            .setWorkflowId(workflowId);

    TestWorkflows.TestSignaledWorkflow workflow1 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflows.TestSignaledWorkflow.class, workflowOptionsBuilder.build());
    WorkflowStub workflowStub1 = WorkflowStub.fromTyped(workflow1);
    WorkflowExecution workflowExecution1 = workflowStub1.start();
    assertNotNull(workflowStub1.getExecution());

    TestWorkflows.TestSignaledWorkflow workflow2 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflows.TestSignaledWorkflow.class, workflowOptionsBuilder.build());
    WorkflowStub workflowStub2 = WorkflowStub.fromTyped(workflow2);
    workflowStub2.start();
    assertNotNull(workflowStub2.getExecution());

    // WORKFLOW_ID_CONFLICT_POLICY_TERMINATE_EXISTING means that calling start with a workflow ID
    // that already has a running workflow will terminate the existing execution.
    assertNotEquals(workflowStub1.getExecution(), workflowStub2.getExecution());
    workflow2.signal("test");
    assertEquals("done", workflowStub2.getResult(String.class));
    assertThrows(
        WorkflowFailedException.class,
        () ->
            testWorkflowRule
                .getWorkflowClient()
                .newUntypedWorkflowStub(
                    Optional.of(TestWorkflows.TestSignaledWorkflow.class.toString()),
                    WorkflowTargetOptions.newBuilder()
                        .setWorkflowExecution(workflowExecution1)
                        .build())
                .getResult(String.class));
  }

  @Test
  public void policyUseExisting() {
    String workflowId = UUID.randomUUID().toString();

    WorkflowOptions.Builder workflowOptionsBuilder =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowIdConflictPolicy(
                WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
            .setWorkflowId(workflowId);

    TestWorkflows.TestSignaledWorkflow workflow1 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflows.TestSignaledWorkflow.class, workflowOptionsBuilder.build());
    WorkflowStub workflowStub1 = WorkflowStub.fromTyped(workflow1);
    workflowStub1.start();
    assertNotNull(workflowStub1.getExecution());

    TestWorkflows.TestSignaledWorkflow workflow2 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflows.TestSignaledWorkflow.class, workflowOptionsBuilder.build());
    WorkflowStub workflowStub2 = WorkflowStub.fromTyped(workflow2);
    workflowStub2.start();
    assertNotNull(workflowStub1.getExecution());

    // WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING means that calling start with a workflow ID
    // that already has a running workflow will return the already running execution.
    assertEquals(workflowStub1.getExecution(), workflowStub2.getExecution());
    workflow2.signal("test");
    assertEquals("done", workflowStub1.getResult(String.class));
    assertEquals("done", workflowStub2.getResult(String.class));
  }

  @Test
  public void policyFail() {
    String workflowId = UUID.randomUUID().toString();

    WorkflowOptions.Builder workflowOptionsBuilder =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL)
            .setWorkflowId(workflowId);

    TestWorkflows.TestSignaledWorkflow workflow1 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflows.TestSignaledWorkflow.class, workflowOptionsBuilder.build());
    WorkflowStub workflowStub1 = WorkflowStub.fromTyped(workflow1);
    workflowStub1.start();
    assertNotNull(workflowStub1.getExecution());

    TestWorkflows.TestSignaledWorkflow workflow2 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflows.TestSignaledWorkflow.class, workflowOptionsBuilder.build());
    WorkflowStub workflowStub2 = WorkflowStub.fromTyped(workflow2);
    // WORKFLOW_ID_CONFLICT_POLICY_FAIL means that calling start with a workflow ID
    // that already has a running workflow will fail.
    assertThrows(WorkflowExecutionAlreadyStarted.class, () -> workflowStub2.start());
    workflow1.signal("test");
    assertEquals("done", workflowStub1.getResult(String.class));
  }

  @Test
  public void policyDefault() {
    String workflowId = UUID.randomUUID().toString();

    WorkflowOptions.Builder workflowOptionsBuilder =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowId(workflowId);

    TestWorkflows.TestSignaledWorkflow workflow1 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflows.TestSignaledWorkflow.class, workflowOptionsBuilder.build());
    WorkflowStub workflowStub1 = WorkflowStub.fromTyped(workflow1);
    workflowStub1.start();
    assertNotNull(workflowStub1.getExecution());

    TestWorkflows.TestSignaledWorkflow workflow2 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflows.TestSignaledWorkflow.class, workflowOptionsBuilder.build());
    WorkflowStub workflowStub2 = WorkflowStub.fromTyped(workflow2);
    // Default policy is WORKFLOW_ID_CONFLICT_POLICY_FAIL
    assertThrows(WorkflowExecutionAlreadyStarted.class, () -> workflowStub2.start());
    workflow1.signal("test");
    assertEquals("done", workflowStub1.getResult(String.class));
  }

  public static class TestWorkflowImpl2 implements TestWorkflows.TestSignaledWorkflow {
    boolean done = false;

    @Override
    public String execute() {
      Workflow.await(() -> done);
      return "done";
    }

    @Override
    public void signal(String arg) {
      done = true;
    }
  }
}
