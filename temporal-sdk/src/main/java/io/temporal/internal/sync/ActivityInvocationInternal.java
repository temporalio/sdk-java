package io.temporal.internal.sync;

import io.temporal.workflow.ActivityInvocationOptions;
import io.temporal.workflow.Functions;
import io.temporal.workflow.Promise;
import java.util.Objects;

/** Captures one typed Activity proxy invocation and its result Promise. */
final class ActivityInvocationInternal {

  private static final ThreadLocal<State> invocation = new ThreadLocal<>();

  private ActivityInvocationInternal() {}

  static <R> Promise<R> invoke(
      ActivityInvocationOptions options, Functions.Proc invocationFunction) {
    if (invocation.get() != null) {
      throw new IllegalStateException("Already invoking an Activity with invocation options");
    }

    State state = new State(Objects.requireNonNull(options, "options"));
    invocation.set(state);
    try {
      invocationFunction.apply();
      return state.getResult();
    } finally {
      invocation.remove();
    }
  }

  static ActivityInvocationOptions consumeOptions() {
    State state = invocation.get();
    if (state == null) {
      throw new IllegalStateException("Not invoking an Activity with invocation options");
    }
    if (state.consumed) {
      throw new IllegalStateException("ActivityInvocationOptions can apply to only one invocation");
    }
    state.consumed = true;
    return state.options;
  }

  static boolean isActive() {
    return invocation.get() != null;
  }

  static <R> void setResult(Promise<R> result) {
    State state = invocation.get();
    if (state == null) {
      throw new IllegalStateException("Not invoking an Activity with invocation options");
    }
    if (state.result != null) {
      throw new IllegalStateException("ActivityInvocationOptions can apply to only one invocation");
    }
    state.result = Objects.requireNonNull(result, "result");
  }

  private static final class State {
    private final ActivityInvocationOptions options;
    private boolean consumed;
    private Promise<?> result;

    private State(ActivityInvocationOptions options) {
      this.options = options;
    }

    @SuppressWarnings("unchecked")
    private <R> Promise<R> getResult() {
      if (!consumed || result == null) {
        throw new IllegalArgumentException(
            "activityMethod must invoke an Activity stub created through Workflow.newActivityStub "
                + "or Workflow.newLocalActivityStub");
      }
      return (Promise<R>) result;
    }
  }
}
