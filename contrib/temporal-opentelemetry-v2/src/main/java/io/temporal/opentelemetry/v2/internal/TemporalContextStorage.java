package io.temporal.opentelemetry.v2.internal;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextStorage;
import io.opentelemetry.context.Scope;
import io.temporal.workflow.WorkflowThreadLocal;
import io.temporal.workflow.unsafe.WorkflowUnsafe;

/** Stores the current OpenTelemetry context in Temporal workflow threads. */
public final class TemporalContextStorage implements ContextStorage {
  private static final WorkflowThreadLocal<Context> WORKFLOW_CONTEXT = new WorkflowThreadLocal<>();

  private final ContextStorage delegate;

  TemporalContextStorage(ContextStorage delegate) {
    this.delegate = delegate;
  }

  static void setWorkflowContext(Context context) {
    if (WorkflowUnsafe.isWorkflowThread()) {
      WORKFLOW_CONTEXT.set(context);
    }
  }

  @Override
  public Scope attach(Context context) {
    if (!WorkflowUnsafe.isWorkflowThread()) {
      return delegate.attach(context);
    }

    Context previous = WORKFLOW_CONTEXT.get();
    if (context == previous) {
      return () -> {};
    }
    WORKFLOW_CONTEXT.set(context);
    return () -> {
      if (WORKFLOW_CONTEXT.get() == context) {
        WORKFLOW_CONTEXT.set(previous);
      }
    };
  }

  @Override
  public Context current() {
    return WorkflowUnsafe.isWorkflowThread() ? WORKFLOW_CONTEXT.get() : delegate.current();
  }

  @Override
  public Context root() {
    return delegate.root();
  }
}
