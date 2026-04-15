package io.temporal.common.interceptors;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.ActivityCompletionClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.Experimental;
import java.util.Optional;

@Experimental
public interface WorkflowClientInterceptor {
  /**
   * Called when workflow stub is instantiated during creation of new workflow. It allows to
   * decorate calls to {@link WorkflowStub} instance which is an entry point for client code.
   *
   * @return decorated stub
   * @deprecated consider implementing all intercepting functionality using {@link
   *     WorkflowClientCallsInterceptor} that is produced in {@link
   *     #workflowClientCallsInterceptor}. This method has to stay temporary because
   *     TimeLockingInterceptor has to intercept top level {@link WorkflowStub} methods.
   */
  @Deprecated
  WorkflowStub newUntypedWorkflowStub(
      String workflowType, WorkflowOptions options, WorkflowStub next);

  /**
   * Called when workflow stub is instantiated for a known existing workflow execution. It allows to
   * decorate calls to {@link WorkflowStub} instance which is an entry point for client code.
   *
   * @return decorated stub
   * @deprecated consider implementing all intercepting functionality using {@link
   *     WorkflowClientCallsInterceptor} that is produced in {@link
   *     #workflowClientCallsInterceptor}. This method has to stay temporary because
   *     TimeLockingInterceptor has to intercept top level {@link WorkflowStub} methods.
   */
  @Deprecated
  WorkflowStub newUntypedWorkflowStub(
      WorkflowExecution execution, Optional<String> workflowType, WorkflowStub next);

  ActivityCompletionClient newActivityCompletionClient(ActivityCompletionClient next);

  /**
   * Called once during creation of WorkflowClient to create a chain of Client Workflow Interceptors
   *
   * @param next next workflow client interceptor in the chain of interceptors
   * @return new interceptor that should decorate calls to {@code next}
   */
  WorkflowClientCallsInterceptor workflowClientCallsInterceptor(
      WorkflowClientCallsInterceptor next);

  /**
   * Called once during creation of an {@link io.temporal.client.ActivityClient} to allow a
   * WorkflowClientInterceptor to also intercept activity client calls. This enables users who
   * maintain both a {@link io.temporal.client.WorkflowClient} and an {@link
   * io.temporal.client.ActivityClient} to share a single interceptor implementation.
   *
   * <p>The primary registration path for activity interceptors is {@link
   * io.temporal.client.ActivityClientOptions#getInterceptors()}.
   *
   * @param next next activity client calls interceptor in the chain
   * @return new interceptor that should decorate calls to {@code next}
   */
  @Experimental
  default ActivityClientCallsInterceptor activityClientCallsInterceptor(
      ActivityClientCallsInterceptor next) {
    return next;
  }
}
