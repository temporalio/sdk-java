/*
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package com.uber.cadence.internal.sync;

import com.google.common.base.Joiner;
import com.google.common.reflect.TypeToken;
import com.uber.cadence.PollForActivityTaskResponse;
import com.uber.cadence.RespondActivityTaskCompletedRequest;
import com.uber.cadence.RespondActivityTaskFailedRequest;
import com.uber.cadence.activity.ActivityMethod;
import com.uber.cadence.client.ActivityCancelledException;
import com.uber.cadence.common.MethodRetry;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.internal.common.CheckedExceptionWrapper;
import com.uber.cadence.internal.common.InternalUtils;
import com.uber.cadence.internal.metrics.MetricsType;
import com.uber.cadence.internal.worker.ActivityTaskHandler;
import com.uber.cadence.serviceclient.IWorkflowService;
import com.uber.cadence.testing.SimulatedTimeoutException;
import com.uber.m3.tally.Scope;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiFunction;

class POJOActivityTaskHandler implements ActivityTaskHandler {

  private final DataConverter dataConverter;
  private final ScheduledExecutorService heartbeatExecutor;
  private final Map<String, ActivityTaskExecutor> activities =
      Collections.synchronizedMap(new HashMap<>());
  private IWorkflowService service;
  private final String domain;

  POJOActivityTaskHandler(
      IWorkflowService service,
      String domain,
      DataConverter dataConverter,
      ScheduledExecutorService heartbeatExecutor) {
    this.service = service;
    this.domain = domain;
    this.dataConverter = dataConverter;
    this.heartbeatExecutor = heartbeatExecutor;
  }

  private void addActivityImplementation(
      Object activity, BiFunction<Method, Object, ActivityTaskExecutor> newTaskExecutor) {
    if (activity instanceof Class) {
      throw new IllegalArgumentException("Activity object instance expected, not the class");
    }
    Class<?> cls = activity.getClass();
    for (Method method : cls.getMethods()) {
      if (method.getAnnotation(ActivityMethod.class) != null) {
        throw new IllegalArgumentException(
            "Found @ActivityMethod annotation on \""
                + method
                + "\" This annotation can be used only on the interface method it implements.");
      }
      if (method.getAnnotation(MethodRetry.class) != null) {
        throw new IllegalArgumentException(
            "Found @MethodRetry annotation on \""
                + method
                + "\" This annotation can be used only on the interface method it implements.");
      }
    }
    TypeToken<?>.TypeSet interfaces = TypeToken.of(cls).getTypes().interfaces();
    if (interfaces.isEmpty()) {
      throw new IllegalArgumentException("Activity must implement at least one interface");
    }
    for (TypeToken<?> i : interfaces) {
      if (i.getType().getTypeName().startsWith("org.mockito")) {
        continue;
      }
      for (Method method : i.getRawType().getMethods()) {
        ActivityMethod annotation = method.getAnnotation(ActivityMethod.class);
        String activityType;
        if (annotation != null && !annotation.name().isEmpty()) {
          activityType = annotation.name();
        } else {
          activityType = InternalUtils.getSimpleName(method);
        }
        if (activities.containsKey(activityType)) {
          throw new IllegalStateException(
              activityType + " activity type is already registered with the worker");
        }

        ActivityTaskExecutor implementation = newTaskExecutor.apply(method, activity);
        activities.put(activityType, implementation);
      }
    }
  }

  private ActivityTaskHandler.Result mapToActivityFailure(
      Throwable failure, Scope metricsScope, boolean isLocalActivity) {

    if (failure instanceof ActivityCancelledException) {
      if (isLocalActivity) {
        metricsScope.counter(MetricsType.LOCAL_ACTIVITY_CANCELED_COUNTER).inc(1);
      }
      throw new CancellationException(failure.getMessage());
    }

    // Only expected during unit tests.
    if (failure instanceof SimulatedTimeoutException) {
      SimulatedTimeoutException timeoutException = (SimulatedTimeoutException) failure;
      failure =
          new SimulatedTimeoutExceptionInternal(
              timeoutException.getTimeoutType(),
              dataConverter.toData(timeoutException.getDetails()));
    }

    if (failure instanceof Error) {
      if (isLocalActivity) {
        metricsScope.counter(MetricsType.LOCAL_ACTIVITY_ERROR_COUNTER).inc(1);
      } else {
        metricsScope.counter(MetricsType.ACTIVITY_TASK_ERROR_COUNTER).inc(1);
      }
      throw (Error) failure;
    }

    if (isLocalActivity) {
      metricsScope.counter(MetricsType.LOCAL_ACTIVITY_FAILED_COUNTER).inc(1);
    } else {
      metricsScope.counter(MetricsType.ACTIVITY_EXEC_FAILED_COUNTER).inc(1);
    }

    RespondActivityTaskFailedRequest result = new RespondActivityTaskFailedRequest();
    failure = CheckedExceptionWrapper.unwrap(failure);
    result.setReason(failure.getClass().getName());
    result.setDetails(dataConverter.toData(failure));
    return new ActivityTaskHandler.Result(
        null, new Result.TaskFailedResult(result, failure), null, null);
  }

  @Override
  public boolean isAnyTypeSupported() {
    return !activities.isEmpty();
  }

  void setActivitiesImplementation(Object[] activitiesImplementation) {
    activities.clear();
    for (Object activity : activitiesImplementation) {
      addActivityImplementation(activity, POJOActivityImplementation::new);
    }
  }

  void setLocalActivitiesImplementation(Object[] activitiesImplementation) {
    activities.clear();
    for (Object activity : activitiesImplementation) {
      addActivityImplementation(activity, POJOLocalActivityImplementation::new);
    }
  }

  @Override
  public Result handle(
      PollForActivityTaskResponse pollResponse, Scope metricsScope, boolean isLocalActivity) {
    String activityType = pollResponse.getActivityType().getName();
    ActivityTaskImpl activityTask = new ActivityTaskImpl(pollResponse);
    ActivityTaskExecutor activity = activities.get(activityType);
    if (activity == null) {
      String knownTypes = Joiner.on(", ").join(activities.keySet());
      return mapToActivityFailure(
          new IllegalArgumentException(
              "Activity Type \""
                  + activityType
                  + "\" is not registered with a worker. Known types are: "
                  + knownTypes),
          metricsScope,
          isLocalActivity);
    }
    return activity.execute(activityTask, metricsScope);
  }

  interface ActivityTaskExecutor {
    ActivityTaskHandler.Result execute(ActivityTaskImpl task, Scope metricsScope);
  }

  private class POJOActivityImplementation implements ActivityTaskExecutor {
    private final Method method;
    private final Object activity;

    POJOActivityImplementation(Method interfaceMethod, Object activity) {
      this.method = interfaceMethod;
      this.activity = activity;
    }

    @Override
    public ActivityTaskHandler.Result execute(ActivityTaskImpl task, Scope metricsScope) {
      ActivityExecutionContext context =
          new ActivityExecutionContextImpl(service, domain, task, dataConverter, heartbeatExecutor);
      byte[] input = task.getInput();
      CurrentActivityExecutionContext.set(context);
      try {
        Object[] args = dataConverter.fromDataArray(input, method.getGenericParameterTypes());
        Object result = method.invoke(activity, args);
        RespondActivityTaskCompletedRequest request = new RespondActivityTaskCompletedRequest();
        if (context.isDoNotCompleteOnReturn()) {
          return new ActivityTaskHandler.Result(null, null, null, null);
        }
        if (method.getReturnType() != Void.TYPE) {
          request.setResult(dataConverter.toData(result));
        }
        return new ActivityTaskHandler.Result(request, null, null, null);
      } catch (RuntimeException | IllegalAccessException e) {
        return mapToActivityFailure(e, metricsScope, false);
      } catch (InvocationTargetException e) {
        return mapToActivityFailure(e.getTargetException(), metricsScope, false);
      } finally {
        CurrentActivityExecutionContext.unset();
      }
    }
  }

  private class POJOLocalActivityImplementation implements ActivityTaskExecutor {
    private final Method method;
    private final Object activity;

    POJOLocalActivityImplementation(Method interfaceMethod, Object activity) {
      this.method = interfaceMethod;
      this.activity = activity;
    }

    @Override
    public ActivityTaskHandler.Result execute(ActivityTaskImpl task, Scope metricsScope) {
      ActivityExecutionContext context =
          new LocalActivityExecutionContextImpl(service, domain, task);
      CurrentActivityExecutionContext.set(context);
      byte[] input = task.getInput();
      try {
        Object[] args = dataConverter.fromDataArray(input, method.getGenericParameterTypes());
        Object result = method.invoke(activity, args);
        RespondActivityTaskCompletedRequest request = new RespondActivityTaskCompletedRequest();
        if (method.getReturnType() != Void.TYPE) {
          request.setResult(dataConverter.toData(result));
        }
        return new ActivityTaskHandler.Result(request, null, null, null);
      } catch (RuntimeException | IllegalAccessException e) {
        return mapToActivityFailure(e, metricsScope, true);
      } catch (InvocationTargetException e) {
        return mapToActivityFailure(e.getTargetException(), metricsScope, true);
      } finally {
        CurrentActivityExecutionContext.unset();
      }
    }
  }

  // This is only for unit test to mock service and set expectations.
  void setWorkflowService(IWorkflowService service) {
    this.service = service;
  }
}
