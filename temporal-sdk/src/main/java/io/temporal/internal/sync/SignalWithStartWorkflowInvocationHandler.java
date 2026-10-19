package io.temporal.internal.sync;

import com.google.common.base.Defaults;
import io.temporal.common.CronSchedule;
import io.temporal.common.MethodRetry;
import io.temporal.common.RetryOptions;
import io.temporal.common.metadata.POJOWorkflowInterfaceMetadata;
import io.temporal.common.metadata.POJOWorkflowMethodMetadata;
import io.temporal.common.metadata.WorkflowMethodType;
import io.temporal.workflow.*;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Captures two typed calls without executing either one before validation completes. */
public final class SignalWithStartWorkflowInvocationHandler implements InvocationHandler {
  private static final ThreadLocal<Capture> CURRENT = new ThreadLocal<>();
  private final Class<?> workflowInterface;
  private final POJOWorkflowInterfaceMetadata metadata;
  private final SignalWithStartWorkflowStub stub;

  private SignalWithStartWorkflowInvocationHandler(
      Class<?> workflowInterface, SignalWithStartWorkflowOptions options) {
    this.workflowInterface = workflowInterface;
    metadata = POJOWorkflowInterfaceMetadata.newInstance(workflowInterface);
    POJOWorkflowMethodMetadata workflow =
        metadata
            .getWorkflowMethod()
            .orElseThrow(
                () ->
                    new IllegalArgumentException("Missing method annotated with @WorkflowMethod"));
    Method method = workflow.getWorkflowMethod();
    SignalWithStartWorkflowOptions base =
        options == null ? SignalWithStartWorkflowOptions.newBuilder().build() : options;
    MethodRetry retry = method.getAnnotation(MethodRetry.class);
    CronSchedule cron = method.getAnnotation(CronSchedule.class);
    SignalWithStartWorkflowOptions merged =
        base.toBuilder()
            .setRetryOptions(RetryOptions.merge(retry, base.getRetryOptions()))
            .setCronSchedule(
                base.getCronSchedule() == null && cron != null
                    ? cron.value()
                    : base.getCronSchedule())
            .build();
    stub = new SignalWithStartWorkflowStubImpl(workflow.getName(), merged);
  }

  @SuppressWarnings("unchecked")
  public static <T> T newStub(Class<T> workflowInterface, SignalWithStartWorkflowOptions options) {
    return (T)
        Proxy.newProxyInstance(
            workflowInterface.getClassLoader(),
            new Class<?>[] {workflowInterface},
            new SignalWithStartWorkflowInvocationHandler(workflowInterface, options));
  }

  private static SignalWithStartWorkflowInvocationHandler handler(Object target) {
    if (target == null
        || !Proxy.isProxyClass(target.getClass())
        || !(Proxy.getInvocationHandler(target)
            instanceof SignalWithStartWorkflowInvocationHandler)) {
      throw new IllegalArgumentException(
          "Expected a stub created through Workflow.newSignalWithStartWorkflowStub");
    }
    return (SignalWithStartWorkflowInvocationHandler) Proxy.getInvocationHandler(target);
  }

  public static SignalWithStartWorkflowStub fromTyped(Object target) {
    return handler(target).stub;
  }

  @SuppressWarnings("unchecked")
  public static <T> T signalWithStart(
      T target, Functions.Proc workflowCall, Functions.Proc signalCall) {
    SignalWithStartWorkflowInvocationHandler handler = handler(target);
    if (CURRENT.get() != null)
      throw new IllegalStateException("Nested signal-with-start capture is not supported");
    Capture capture = new Capture(handler);
    CURRENT.set(capture);
    try {
      workflowCall.apply();
      if (capture.startArgs == null)
        throw new IllegalArgumentException("Expected exactly one workflow call");
      capture.signalPhase = true;
      signalCall.apply();
      if (capture.signalArgs == null)
        throw new IllegalArgumentException("Expected exactly one signal call");
    } finally {
      CURRENT.remove();
    }
    ExternalWorkflowStub external =
        handler.stub.signalWithStart(capture.signalName, capture.signalArgs, capture.startArgs);
    return (T) Workflow.newExternalWorkflowStub(handler.workflowInterface, external.getExecution());
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) {
    if (method.getDeclaringClass() == Object.class) {
      switch (method.getName()) {
        case "toString":
          return "SignalWithStartWorkflowStub{" + stub.getWorkflowType() + "}";
        case "hashCode":
          return System.identityHashCode(proxy);
        case "equals":
          return proxy == args[0];
        default:
          throw new IllegalArgumentException("Unsupported Object method");
      }
    }
    Capture capture = CURRENT.get();
    if (capture == null || capture.handler != this)
      throw new IllegalStateException(
          "Calls must use the target stub inside Workflow.signalWithStart");
    POJOWorkflowMethodMetadata entry = metadata.getMethodMetadata(method);
    Object[] values = args == null ? new Object[0] : args.clone();
    if (!capture.signalPhase
        && entry.getType() == WorkflowMethodType.WORKFLOW
        && capture.startArgs == null) {
      capture.startArgs = values;
    } else if (capture.signalPhase
        && entry.getType() == WorkflowMethodType.SIGNAL
        && capture.signalArgs == null) {
      capture.signalName = entry.getName();
      capture.signalArgs = values;
    } else {
      throw new IllegalArgumentException(
          "Capture requires exactly one workflow call followed by exactly one signal call");
    }
    return method.getReturnType() == Void.TYPE
        ? null
        : Defaults.defaultValue(method.getReturnType());
  }

  private static final class Capture {
    private final SignalWithStartWorkflowInvocationHandler handler;
    private boolean signalPhase;
    private Object[] startArgs;
    private String signalName;
    private Object[] signalArgs;

    private Capture(SignalWithStartWorkflowInvocationHandler handler) {
      this.handler = handler;
    }
  }
}
