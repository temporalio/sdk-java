package io.temporal.internal.sync;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.nexus.system.workflowservice.SignalWithStartWorkflowRequest;
import io.temporal.nexus.system.workflowservice.SignalWithStartWorkflowResponse;
import io.temporal.nexus.system.workflowservice.UserMetadata;
import io.temporal.nexus.system.workflowservice.WorkflowServiceOperations;
import io.temporal.workflow.*;
import java.util.Arrays;
import java.util.Objects;

public final class SignalWithStartWorkflowStubImpl implements SignalWithStartWorkflowStub {
  private final String workflowType;
  private final SignalWithStartWorkflowOptions options;

  public SignalWithStartWorkflowStubImpl(
      String workflowType, SignalWithStartWorkflowOptions options) {
    this.workflowType = Objects.requireNonNull(workflowType, "workflowType");
    this.options = options == null ? SignalWithStartWorkflowOptions.newBuilder().build() : options;
  }

  @Override
  public String getWorkflowType() {
    return workflowType;
  }

  @Override
  public SignalWithStartWorkflowOptions getOptions() {
    return options;
  }

  @SuppressWarnings("deprecation")
  @Override
  public ExternalWorkflowStub signalWithStart(
      String signalName, Object[] signalArgs, Object... startArgs) {
    WorkflowInternal.assertNotReadOnly("signal with start workflow");
    if (workflowType.isEmpty())
      throw new IllegalArgumentException("workflowType must not be empty");
    if (signalName == null || signalName.isEmpty())
      throw new IllegalArgumentException("signalName must not be empty");
    String id =
        options.getWorkflowId() == null
            ? Workflow.randomUUID().toString()
            : options.getWorkflowId();
    if (id.isEmpty()) throw new IllegalArgumentException("workflowId must not be empty");
    String queue =
        options.getTaskQueue() == null ? Workflow.getInfo().getTaskQueue() : options.getTaskQueue();
    if (queue.isEmpty()) throw new IllegalArgumentException("taskQueue must not be empty");
    if (options.getWorkflowIdReusePolicy()
            == WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_TERMINATE_IF_RUNNING
        && options.getWorkflowIdConflictPolicy() != null) {
      throw new IllegalArgumentException(
          "workflowIdConflictPolicy cannot be combined with terminate-if-running");
    }
    SignalWithStartWorkflowRequest request =
        SignalWithStartWorkflowRequest.newBuilder()
            .setWorkflow(workflowType)
            .setSignal(signalName)
            .setId(id)
            .setTaskQueue(queue)
            .setArgs(Arrays.asList(startArgs == null ? new Object[0] : startArgs.clone()))
            .setSignalArgs(Arrays.asList(signalArgs == null ? new Object[0] : signalArgs.clone()))
            .setExecutionTimeout(options.getWorkflowExecutionTimeout())
            .setRunTimeout(options.getWorkflowRunTimeout())
            .setTaskTimeout(options.getWorkflowTaskTimeout())
            .setIdReusePolicy(
                options.getWorkflowIdReusePolicy() == null
                    ? WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE
                    : options.getWorkflowIdReusePolicy())
            .setIdConflictPolicy(options.getWorkflowIdConflictPolicy())
            .setRetryPolicy(options.getRetryOptions())
            .setCronSchedule(options.getCronSchedule())
            .setMemo(options.getMemo())
            .setSearchAttributes(options.getTypedSearchAttributes())
            .setPriority(options.getPriority())
            .setVersioningOverride(options.getVersioningOverride())
            .setStartDelay(options.getStartDelay())
            .setRequestId(
                options.getRequestId() == null
                    ? Workflow.randomUUID().toString()
                    : options.getRequestId())
            .setUserMetadata(
                UserMetadata.newBuilder()
                    .setStaticSummary(options.getStaticSummary())
                    .setStaticDetails(options.getStaticDetails())
                    .build())
            .build();
    SignalWithStartWorkflowResponse response =
        WorkflowServiceOperations.signalWithStartWorkflow(request);
    WorkflowExecution.Builder execution = WorkflowExecution.newBuilder().setWorkflowId(id);
    if (response.getRunId() != null && !response.getRunId().isEmpty())
      execution.setRunId(response.getRunId());
    return Workflow.newUntypedExternalWorkflowStub(execution.build());
  }
}
