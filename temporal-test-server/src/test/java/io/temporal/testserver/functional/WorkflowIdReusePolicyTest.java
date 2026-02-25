package io.temporal.testserver.functional;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.client.*;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.testserver.functional.common.TestWorkflows;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowIdReusePolicyTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(ForeverWorkflowImpl.class, FailingWorkflowImpl.class)
          .build();

  @Test
  public void rejectDuplicateStopsAnotherAfterFailed() {
    String workflowId = "reject-duplicate-1";
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowId(workflowId)
            .setWorkflowTaskTimeout(Duration.ofSeconds(1))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
            .build();

    WorkflowExecution execution1 = runFailingWorkflow(options);
    describe(execution1).assertStatus(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_FAILED);

    Assert.assertThrows(WorkflowExecutionAlreadyStarted.class, () -> startForeverWorkflow(options));
  }

  @Test
  public void allowDuplicateAfterFailed() {
    String workflowId = "allow-duplicate-1";
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowId(workflowId)
            .setWorkflowTaskTimeout(Duration.ofSeconds(1))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
            .build();

    WorkflowExecution execution1 = runFailingWorkflow(options);
    describe(execution1).assertStatus(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_FAILED);

    WorkflowExecution execution2 = startForeverWorkflow(options);
    describe(execution2).assertStatus(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING);
  }

  @Test
  public void alreadyRunningWorkflowBlocksSecondEvenWithAllowDuplicate() {
    String workflowId = "allow-duplicate-2";
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowId(workflowId)
            .setWorkflowTaskTimeout(Duration.ofSeconds(1))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
            .build();

    WorkflowExecution execution1 = startForeverWorkflow(options);
    describe(execution1).assertStatus(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING);

    Assert.assertThrows(WorkflowExecutionAlreadyStarted.class, () -> startForeverWorkflow(options));
    describe(execution1).assertStatus(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING);
  }

  @Test
  public void secondWorkflowTerminatesFirst() {
    String workflowId = "terminate-if-running-1";
    @SuppressWarnings("deprecation")
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowId(workflowId)
            .setWorkflowTaskTimeout(Duration.ofSeconds(1))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_TERMINATE_IF_RUNNING)
            .build();

    WorkflowExecution execution1 = startForeverWorkflow(options);
    describe(execution1).assertStatus(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING);

    WorkflowExecution execution2 = startForeverWorkflow(options);
    describe(execution1).assertStatus(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TERMINATED);
    describe(execution2).assertStatus(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING);
  }

  @Test
  public void deduplicateRequestWorkflowStillRunning() {
    String workflowId = "deduplicate-request-1";
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowId(workflowId)
            .setWorkflowTaskTimeout(Duration.ofSeconds(1))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setRequestId("request-id-1")
            .build();

    WorkflowExecution execution1 = startForeverWorkflow(options);
    describe(execution1).assertStatus(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING);

    WorkflowExecution execution2 = startForeverWorkflow(options);
    describe(execution2).assertExecutionId(execution1);
  }

  @Test
  public void deduplicateRequestWorkflowAlreadyCompleted() {
    String workflowId = "deduplicate-request-2";
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowId(workflowId)
            .setWorkflowTaskTimeout(Duration.ofSeconds(1))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setRequestId("request-id-2")
            .build();

    WorkflowExecution execution1 = runFailingWorkflow(options);
    describe(execution1).assertStatus(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_FAILED);

    WorkflowExecution execution2 = startForeverWorkflow(options);
    describe(execution2).assertExecutionId(execution1);
  }

  @Test
  public void invalidWorkflowIdReusePolicy() {
    String workflowId = "invalid-workflow-id-reuse-policy";
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowId(workflowId)
            .setWorkflowTaskTimeout(Duration.ofSeconds(1))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
            .setWorkflowIdConflictPolicy(
                WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_TERMINATE_EXISTING)
            .build();

    Assert.assertThrows(WorkflowServiceException.class, () -> startForeverWorkflow(options));
  }

  private WorkflowExecution startForeverWorkflow(WorkflowOptions options) {
    TestWorkflows.PrimitiveWorkflow workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.PrimitiveWorkflow.class, options);
    WorkflowClient.start(workflowStub::execute);
    return WorkflowStub.fromTyped(workflowStub).getExecution();
  }

  private WorkflowExecution runFailingWorkflow(WorkflowOptions options) {
    TestWorkflows.WorkflowReturnsString workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.WorkflowReturnsString.class, options);
    Assert.assertThrows(WorkflowFailedException.class, workflowStub::execute);
    return WorkflowStub.fromTyped(workflowStub).getExecution();
  }

  private DescribeWorkflowAsserter describe(WorkflowExecution execution) {
    DescribeWorkflowAsserter result =
        new DescribeWorkflowAsserter(
            testWorkflowRule
                .getWorkflowClient()
                .getWorkflowServiceStubs()
                .blockingStub()
                .describeWorkflowExecution(
                    DescribeWorkflowExecutionRequest.newBuilder()
                        .setNamespace(
                            testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
                        .setExecution(execution)
                        .build()));

    // There are some assertions that we can always make...
    return result
        .assertExecutionId(execution)
        .assertSaneTimestamps()
        .assertTaskQueue(testWorkflowRule.getTaskQueue());
  }

  public static class ForeverWorkflowImpl implements TestWorkflows.PrimitiveWorkflow {
    @Override
    public void execute() {
      // wait forever to keep it in running state
      Workflow.await(() -> false);
    }
  }

  public static class FailingWorkflowImpl implements TestWorkflows.WorkflowReturnsString {
    @Override
    public String execute() {
      throw ApplicationFailure.newNonRetryableFailure("It's done", "someFailure");
    }
  }
}
