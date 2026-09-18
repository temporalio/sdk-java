package io.temporal.internal.sync;

import io.temporal.workflow.ActivityInvocationOptions;
import io.temporal.workflow.Functions;
import io.temporal.workflow.Promise;
import java.util.Objects;

/** Applies options to one typed Activity proxy invocation. */
final class ActivityInvocationInternal {

  private static final ThreadLocal<State> invocation = new ThreadLocal<>();
  private static final ActivityInvocationOptions DEFAULT_OPTIONS =
      ActivityInvocationOptions.newBuilder().build();

  private ActivityInvocationInternal() {}

  static ActivityInvocationOptions getDefaultOptions() {
    return DEFAULT_OPTIONS;
  }

  static <R> R invoke(ActivityInvocationOptions options, Functions.Func<R> invocationFunction) {
    State state = startInvocation(options, false);
    try {
      R result = invocationFunction.apply();
      state.verifyConsumed();
      return result;
    } finally {
      invocation.remove();
    }
  }

  static void invoke(ActivityInvocationOptions options, Functions.Proc invocationFunction) {
    State state = startInvocation(options, false);
    try {
      invocationFunction.apply();
      state.verifyConsumed();
    } finally {
      invocation.remove();
    }
  }

  static <R> Promise<R> invokeAsync(
      ActivityInvocationOptions options, Functions.Proc invocationFunction) {
    State state = startInvocation(options, true);
    try {
      invocationFunction.apply();
      return state.getResult();
    } finally {
      invocation.remove();
    }
  }

  private static State startInvocation(ActivityInvocationOptions options, boolean async) {
    if (invocation.get() != null) {
      throw new IllegalStateException("Already invoking an Activity with invocation options");
    }

    State state = new State(Objects.requireNonNull(options, "options"), async);
    invocation.set(state);
    return state;
  }

  static ActivityInvocationOptions consumeOptions() {
    State state = invocation.get();
    if (state == null) {
      return DEFAULT_OPTIONS;
    }
    if (state.consumed) {
      throw new IllegalStateException("ActivityInvocationOptions can apply to only one invocation");
    }
    state.consumed = true;
    return state.options;
  }

  static <R> boolean captureResult(Promise<R> result) {
    State state = invocation.get();
    if (state == null || !state.async) {
      return false;
    }
    if (state.result != null) {
      throw new IllegalStateException("ActivityInvocationOptions can apply to only one invocation");
    }
    state.result = Objects.requireNonNull(result, "result");
    return true;
  }

  private static final class State {
    private final ActivityInvocationOptions options;
    private final boolean async;
    private boolean consumed;
    private Promise<?> result;

    private State(ActivityInvocationOptions options, boolean async) {
      this.options = options;
      this.async = async;
    }

    private void verifyConsumed() {
      if (!consumed) {
        throw invalidInvocation();
      }
    }

    @SuppressWarnings("unchecked")
    private <R> Promise<R> getResult() {
      if (!consumed || result == null) {
        throw invalidInvocation();
      }
      return (Promise<R>) result;
    }

    private IllegalArgumentException invalidInvocation() {
      return new IllegalArgumentException(
          "activityMethod must invoke an Activity stub created through Workflow.newActivityStub "
              + "or Workflow.newLocalActivityStub");
    }
  }
}
