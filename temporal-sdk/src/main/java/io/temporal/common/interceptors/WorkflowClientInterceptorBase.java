package io.temporal.common.interceptors;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.ActivityCompletionClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.util.Optional;

/** Convenience base class for WorkflowClientInterceptor implementations. */
public class WorkflowClientInterceptorBase implements WorkflowClientInterceptor {

  @Deprecated
  @Override
  public WorkflowStub newUntypedWorkflowStub(
      String workflowType, WorkflowOptions options, WorkflowStub next) {
    return next;
  }

  @Deprecated
  @Override
  public WorkflowStub newUntypedWorkflowStub(
      WorkflowExecution execution, Optional<String> workflowType, WorkflowStub next) {
    return next;
  }

  @Override
  public ActivityCompletionClient newActivityCompletionClient(ActivityCompletionClient next) {
    return next;
  }

  @Override
  public NexusServiceClientInterceptor nexusServiceClientInterceptor(
      NexusServiceClientInterceptor next) {
    return next;
  }

  @Override
  public WorkflowClientCallsInterceptor workflowClientCallsInterceptor(
      WorkflowClientCallsInterceptor next) {
    return next;
  }
}
