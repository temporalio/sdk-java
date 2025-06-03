package io.temporal.internal.sync;

import io.temporal.activity.LocalActivityOptions;
import io.temporal.common.interceptors.Header;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.workflow.ActivityStub;
import io.temporal.workflow.Functions;
import io.temporal.workflow.Promise;
import java.lang.reflect.Type;

class LocalActivityStubImpl extends ActivityStubBase {
  protected final LocalActivityOptions options;
  private final WorkflowOutboundCallsInterceptor activityExecutor;
  private final Functions.Proc assertReadOnly;

  private void assertSameWorkflow() {
    if (activityExecutor != WorkflowInternal.getWorkflowOutboundInterceptor()) {
      throw new IllegalStateException(
          "Local Activity stub belongs to a different workflow. Create a new stub for each workflow instance.");
    }
  }

  static ActivityStub newInstance(
      LocalActivityOptions options,
      WorkflowOutboundCallsInterceptor activityExecutor,
      Functions.Proc assertReadOnly) {
    LocalActivityOptions validatedOptions =
        LocalActivityOptions.newBuilder(options).validateAndBuildWithDefaults();
    return new LocalActivityStubImpl(validatedOptions, activityExecutor, assertReadOnly);
  }

  private LocalActivityStubImpl(
      LocalActivityOptions options,
      WorkflowOutboundCallsInterceptor activityExecutor,
      Functions.Proc assertReadOnly) {
    this.options = options;
    this.activityExecutor = activityExecutor;
    this.assertReadOnly = assertReadOnly;
  }

  @Override
  public <R> Promise<R> executeAsync(
      String activityName, Class<R> resultClass, Type resultType, Object... args) {
    assertSameWorkflow();
    this.assertReadOnly.apply();
    return activityExecutor
        .executeLocalActivity(
            new WorkflowOutboundCallsInterceptor.LocalActivityInput<>(
                activityName, resultClass, resultType, args, options, Header.empty()))
        .getResult();
  }
}
