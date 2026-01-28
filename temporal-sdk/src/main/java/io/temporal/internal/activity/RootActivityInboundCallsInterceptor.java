package io.temporal.internal.activity;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.activity.DynamicActivity;
import io.temporal.common.converter.EncodedValues;
import io.temporal.common.interceptors.ActivityInboundCallsInterceptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

abstract class RootActivityInboundCallsInterceptor implements ActivityInboundCallsInterceptor {
  private ActivityExecutionContext context;

  @Override
  public void init(ActivityExecutionContext context) {
    this.context = context;
  }

  @Override
  public ActivityOutput execute(ActivityInput input) {
    CurrentActivityExecutionContext.set(context);
    try {
      Object result = executeActivity(input);
      return new ActivityOutput(result);
    } finally {
      CurrentActivityExecutionContext.unset();
    }
  }

  protected abstract Object executeActivity(ActivityInput input);

  static class POJOActivityInboundCallsInterceptor extends RootActivityInboundCallsInterceptor {
    private final Object activity;
    private final Method method;

    POJOActivityInboundCallsInterceptor(Object activity, Method method) {
      this.activity = activity;
      this.method = method;
    }

    @Override
    protected Object executeActivity(ActivityInput input) {
      try {
        return method.invoke(activity, input.getArguments());
      } catch (InvocationTargetException e) {
        throw Activity.wrap(e.getTargetException());
      } catch (Exception e) {
        throw Activity.wrap(e);
      }
    }
  }

  static class DynamicActivityInboundCallsInterceptor extends RootActivityInboundCallsInterceptor {
    private final DynamicActivity activity;

    DynamicActivityInboundCallsInterceptor(DynamicActivity activity) {
      this.activity = activity;
    }

    @Override
    protected Object executeActivity(ActivityInput input) {
      try {
        return activity.execute((EncodedValues) input.getArguments()[0]);
      } catch (Exception e) {
        throw Activity.wrap(e);
      }
    }
  }
}
