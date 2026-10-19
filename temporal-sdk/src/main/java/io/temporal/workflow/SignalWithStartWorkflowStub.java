package io.temporal.workflow;

import io.temporal.common.Experimental;
import io.temporal.internal.sync.SignalWithStartWorkflowInvocationHandler;

/** Untyped workflow-side stub for atomic signal-with-start through system Nexus. */
@Experimental
public interface SignalWithStartWorkflowStub {
  static SignalWithStartWorkflowStub fromTyped(Object stub) {
    return SignalWithStartWorkflowInvocationHandler.fromTyped(stub);
  }

  String getWorkflowType();

  SignalWithStartWorkflowOptions getOptions();

  /** Waits for signal delivery/start, then returns a stub for signaling or cancellation. */
  ExternalWorkflowStub signalWithStart(String signalName, Object[] signalArgs, Object... startArgs);
}
