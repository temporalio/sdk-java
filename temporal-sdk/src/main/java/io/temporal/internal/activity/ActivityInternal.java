package io.temporal.internal.activity;

import io.temporal.activity.ActivityExecutionContext;

public final class ActivityInternal {

  private ActivityInternal() {}

  public static ActivityExecutionContext getExecutionContext() {
    return CurrentActivityExecutionContext.get();
  }
}
