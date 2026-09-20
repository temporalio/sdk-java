package io.temporal.internal.sync;

import com.google.common.base.Defaults;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.ActivityStub;
import io.temporal.workflow.Promise;
import java.lang.reflect.Type;

/** Supports calling an activity by name and arguments without its strongly typed interface. */
abstract class ActivityStubBase implements ActivityStub {

  @Override
  public <T> T execute(String activityName, Class<T> resultClass, Object... args) {
    return execute(activityName, resultClass, resultClass, args);
  }

  @Override
  public <T> T execute(String activityName, Class<T> resultClass, Type resultType, Object... args) {
    return execute(activityName, resultClass, resultType, null, args);
  }

  <T> T execute(
      String activityName, Class<T> resultClass, Type resultType, Type[] argTypes, Object... args) {
    Promise<T> result = executeAsync(activityName, resultClass, resultType, argTypes, args);
    if (AsyncInternal.isAsync()) {
      AsyncInternal.setAsyncResult(result);
      return Defaults.defaultValue(resultClass);
    }
    try {
      return result.get();
    } catch (ActivityFailure e) {
      // Reset stack to the current one. Otherwise it is very confusing to see a stack of
      // an event handling method.
      StackTraceElement[] currentStackTrace = Thread.currentThread().getStackTrace();
      e.setStackTrace(currentStackTrace);
      throw e;
    }
  }

  @Override
  public <R> Promise<R> executeAsync(String activityName, Class<R> resultClass, Object... args) {
    return executeAsync(activityName, resultClass, resultClass, args);
  }

  @Override
  public abstract <R> Promise<R> executeAsync(
      String activityName, Class<R> resultClass, Type resultType, Object... args);

  abstract <R> Promise<R> executeAsync(
      String activityName, Class<R> resultClass, Type resultType, Type[] argTypes, Object... args);
}
