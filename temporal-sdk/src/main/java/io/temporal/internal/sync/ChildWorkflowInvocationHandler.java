package io.temporal.internal.sync;

import static io.temporal.internal.common.InternalUtils.getValueOrDefault;

import io.temporal.common.CronSchedule;
import io.temporal.common.MethodRetry;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.common.metadata.POJOWorkflowInterfaceMetadata;
import io.temporal.common.metadata.POJOWorkflowMethodMetadata;
import io.temporal.common.metadata.WorkflowMethodType;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.ChildWorkflowStub;
import io.temporal.workflow.Functions;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Optional;

/** Dynamic implementation of a strongly typed child workflow interface. */
class ChildWorkflowInvocationHandler implements InvocationHandler {

  private final ChildWorkflowStub stub;
  private final POJOWorkflowInterfaceMetadata workflowMetadata;

  ChildWorkflowInvocationHandler(
      Class<?> workflowInterface,
      ChildWorkflowOptions options,
      WorkflowOutboundCallsInterceptor outboundCallsInterceptor,
      Functions.Proc1<String> assertReadOnly) {
    workflowMetadata = POJOWorkflowInterfaceMetadata.newInstance(workflowInterface);
    Optional<POJOWorkflowMethodMetadata> workflowMethodMetadata =
        workflowMetadata.getWorkflowMethod();
    if (!workflowMethodMetadata.isPresent()) {
      throw new IllegalArgumentException(
          "Missing method annotated with @WorkflowMethod: " + workflowInterface.getName());
    }
    Method workflowMethod = workflowMethodMetadata.get().getWorkflowMethod();
    MethodRetry retryAnnotation = workflowMethod.getAnnotation(MethodRetry.class);
    CronSchedule cronSchedule = workflowMethod.getAnnotation(CronSchedule.class);
    ChildWorkflowOptions merged =
        ChildWorkflowOptions.newBuilder(options)
            .setMethodRetry(retryAnnotation)
            .setCronSchedule(cronSchedule)
            .validateAndBuildWithDefaults();
    this.stub =
        new ChildWorkflowStubImpl(
            workflowMethodMetadata.get().getName(),
            merged,
            outboundCallsInterceptor,
            assertReadOnly);
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) {
    // Proxy the toString method so the stub can be inspected when debugging.
    try {
      if (method.equals(Object.class.getMethod("toString"))) {
        return proxyToString();
      }
    } catch (NoSuchMethodException e) {
      throw new Error("unexpected", e);
    }
    // Implement StubMarker
    if (method.getName().equals(StubMarker.GET_UNTYPED_STUB_METHOD)) {
      return stub;
    }
    POJOWorkflowMethodMetadata methodMetadata = workflowMetadata.getMethodMetadata(method);
    WorkflowMethodType type = methodMetadata.getType();

    if (type == WorkflowMethodType.WORKFLOW) {
      return getValueOrDefault(
          stub.execute(method.getReturnType(), method.getGenericReturnType(), args),
          method.getReturnType());
    }
    if (type == WorkflowMethodType.SIGNAL) {
      stub.signal(methodMetadata.getName(), args);
      return null;
    }
    if (type == WorkflowMethodType.QUERY) {
      throw new UnsupportedOperationException(
          "Query is not supported from workflow to workflow. "
              + "Use an activity that performs the query instead.");
    }
    if (type == WorkflowMethodType.UPDATE) {
      throw new UnsupportedOperationException(
          "Update is not supported from workflow to workflow. "
              + "Use an activity that performs the update instead.");
    }
    throw new IllegalArgumentException("unreachable");
  }

  private String proxyToString() {
    return "ChildWorkflowProxy{"
        + "workflowType='"
        + stub.getWorkflowType()
        + '\''
        + ", options="
        + stub.getOptions()
        + '}';
  }
}
