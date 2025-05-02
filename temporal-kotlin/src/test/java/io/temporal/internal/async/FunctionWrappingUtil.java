package io.temporal.internal.async;

import io.temporal.workflow.Functions;

public class FunctionWrappingUtil {
  /**
   * We emulate here what happens in Async/AsyncInternal when {@link
   * io.temporal.workflow.Async#function(Functions.Func)} accepts {@link Functions.Func<R>} as a
   * parameter and kotlin method reference is getting wrapped into {@link Functions.Func<R>}
   */
  public static <R> Functions.Func<R> temporalJavaFunctionalWrapper(Functions.Func<R> function) {
    return function;
  }
}
