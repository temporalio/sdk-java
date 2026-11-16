package io.temporal.internal.sync;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.common.metadata.POJOWorkflowInterfaceMetadata;
import io.temporal.common.metadata.POJOWorkflowMethodMetadata;
import io.temporal.common.metadata.WorkflowMethodType;
import io.temporal.internal.common.InternalUtils;
import io.temporal.internal.common.JavaLambdaUtils;
import io.temporal.workflow.ExternalWorkflowStub;
import io.temporal.workflow.Functions;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.Objects;

/** Dynamic implementation of a strongly typed external workflow interface. */
public class ExternalWorkflowInvocationHandler implements InvocationHandler {

  private static final ThreadLocal<ProbeContext> probeContext = new ThreadLocal<>();
  private static final ThreadLocal<ProbeResult> probeResult = new ThreadLocal<>();

  /** Probes one invocation without starting a workflow or sending a signal. */
  public static ProbeResult probe(
      Object method, Functions.Proc invocation, WorkflowMethodType expected) {
    Objects.requireNonNull(invocation, "invocation");
    if (probeContext.get() != null) {
      throw new IllegalStateException("Already probing an external workflow invocation");
    }
    SerializedLambda reference =
        JavaLambdaUtils.toSerializedLambda(Objects.requireNonNull(method, "method"));
    Object target = JavaLambdaUtils.getTarget(reference);
    if (reference == null
        || reference.getImplMethodKind() != MethodHandleInfo.REF_invokeInterface
        || target == null
        || !Proxy.isProxyClass(target.getClass())
        || !(Proxy.getInvocationHandler(target) instanceof ExternalWorkflowInvocationHandler)) {
      throw new IllegalArgumentException(
          "Expected a bound method from Workflow.newExternalWorkflowStub");
    }
    ProbeContext context = new ProbeContext(expected);
    probeContext.set(context);
    try {
      invocation.apply();
      ProbeResult result = probeResult.get();
      if (context.invocations != 1 || result == null) {
        throw new IllegalArgumentException(
            "Expected exactly one external workflow " + expected + " invocation");
      }
      return result;
    } finally {
      probeResult.remove();
      probeContext.remove();
    }
  }

  private static final class ProbeContext {
    private final WorkflowMethodType expected;
    private int invocations;

    private ProbeContext(WorkflowMethodType expected) {
      this.expected = Objects.requireNonNull(expected, "expected");
    }
  }

  /** Metadata captured from a bound external workflow method. */
  public static final class ProbeResult {
    private final ExternalWorkflowStub stub;
    private final String name;
    private final Object[] arguments;
    private final Class<?> resultClass;
    private final Type resultType;
    private final Method method;

    private ProbeResult(
        ExternalWorkflowStub stub, POJOWorkflowMethodMetadata metadata, Object[] args) {
      this.stub = stub;
      this.name = metadata.getName();
      this.arguments = args == null ? new Object[0] : args.clone();
      this.method = metadata.getWorkflowMethod();
      this.resultClass = method.getReturnType();
      this.resultType = method.getGenericReturnType();
    }

    public ExternalWorkflowStub getStub() {
      return stub;
    }

    public String getName() {
      return name;
    }

    public Object[] getArguments() {
      return arguments.clone();
    }

    public Class<?> getResultClass() {
      return resultClass;
    }

    public Type getResultType() {
      return resultType;
    }

    public Method getMethod() {
      return method;
    }
  }

  private final ExternalWorkflowStub stub;
  private final POJOWorkflowInterfaceMetadata workflowMetadata;

  public ExternalWorkflowInvocationHandler(
      Class<?> workflowInterface,
      WorkflowExecution execution,
      WorkflowOutboundCallsInterceptor workflowOutboundCallsInterceptor,
      Functions.Proc1<String> assertReadOnly) {
    this.workflowMetadata = POJOWorkflowInterfaceMetadata.newInstance(workflowInterface, false);
    this.stub =
        new ExternalWorkflowStubImpl(execution, workflowOutboundCallsInterceptor, assertReadOnly);
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
    ProbeContext context = probeContext.get();
    if (context != null) {
      if (++context.invocations != 1 || methodMetadata.getType() != context.expected) {
        throw new IllegalArgumentException(
            "Expected exactly one external workflow " + context.expected + " invocation");
      }
      probeResult.set(new ProbeResult(stub, methodMetadata, args));
      return InternalUtils.getValueOrDefault(null, method.getReturnType());
    }
    switch (methodMetadata.getType()) {
      case QUERY:
        throw new UnsupportedOperationException(
            "Query is not supported from workflow to workflow. "
                + "Use activity that perform the query instead.");
      case WORKFLOW:
        throw new IllegalStateException(
            "Cannot start a workflow with an external workflow stub "
                + "created through Workflow.newExternalWorkflowStub");
      case SIGNAL:
        stub.signal(methodMetadata.getName(), args);
        break;
      case UPDATE:
        throw new UnsupportedOperationException(
            "Cannot update a workflow with an external workflow stub "
                + "created through Workflow.newExternalWorkflowStub");
      default:
        throw new IllegalStateException("unreachable");
    }
    return null;
  }

  private String proxyToString() {
    return "ExternalWorkflowProxy{"
        + "workflowType='"
        + workflowMetadata.getWorkflowType().orElse("")
        + '\''
        + ", execution="
        + stub.getExecution()
        + '}';
  }
}
