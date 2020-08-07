/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
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

package io.temporal.internal.sync;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.uber.m3.tally.Scope;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.failure.v1.CanceledFailureInfo;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
import io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedRequest;
import io.temporal.api.workflowservice.v1.RespondActivityTaskFailedRequest;
import io.temporal.client.ActivityCancelledException;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.interceptors.ActivityInboundCallsInterceptor;
import io.temporal.common.interceptors.ActivityInterceptor;
import io.temporal.failure.FailureConverter;
import io.temporal.failure.TemporalFailure;
import io.temporal.failure.TimeoutFailure;
import io.temporal.internal.common.CheckedExceptionWrapper;
import io.temporal.internal.metrics.MetricsType;
import io.temporal.internal.replay.FailureWrapperException;
import io.temporal.internal.worker.ActivityTaskHandler;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.testing.SimulatedTimeoutFailure;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class POJOActivityTaskHandler implements ActivityTaskHandler {

  private static final Logger log = LoggerFactory.getLogger(POJOActivityTaskHandler.class);

  private final DataConverter dataConverter;
  private final ScheduledExecutorService heartbeatExecutor;
  private final Map<String, ActivityTaskExecutor> activities =
      Collections.synchronizedMap(new HashMap<>());
  private final WorkflowServiceStubs service;
  private final String namespace;
  private final ActivityInterceptor[] interceptors;

  POJOActivityTaskHandler(
      WorkflowServiceStubs service,
      String namespace,
      DataConverter dataConverter,
      ScheduledExecutorService heartbeatExecutor,
      ActivityInterceptor[] interceptors) {
    this.service = Objects.requireNonNull(service);
    this.namespace = Objects.requireNonNull(namespace);
    this.dataConverter = Objects.requireNonNull(dataConverter);
    this.heartbeatExecutor = Objects.requireNonNull(heartbeatExecutor);
    this.interceptors = Objects.requireNonNull(interceptors);
  }

  private void addActivityImplementation(
      Object activity, BiFunction<Method, Object, ActivityTaskExecutor> newTaskExecutor) {
    if (activity instanceof Class) {
      throw new IllegalArgumentException("Activity object instance expected, not the class");
    }
    Class<?> cls = activity.getClass();
    POJOActivityImplMetadata activityMetadata = POJOActivityImplMetadata.newInstance(cls);
    for (String activityType : activityMetadata.getActivityTypes()) {
      if (activities.containsKey(activityType)) {
        throw new IllegalArgumentException(
            "\"" + activityType + "\" activity type is already registered with the worker");
      }
      Method method = activityMetadata.getMethodMetadata(activityType).getMethod();
      ActivityTaskExecutor implementation = newTaskExecutor.apply(method, activity);
      activities.put(activityType, implementation);
    }
  }

  private ActivityTaskHandler.Result mapToActivityFailure(
      Throwable exception, String activityId, Scope metricsScope, boolean isLocalActivity) {
    exception = CheckedExceptionWrapper.unwrap(exception);
    if (exception instanceof ActivityCancelledException) {
      if (isLocalActivity) {
        metricsScope.counter(MetricsType.LOCAL_ACTIVITY_CANCELED_COUNTER).inc(1);
      }
      String stackTrace = FailureConverter.serializeStackTrace(exception);
      throw new FailureWrapperException(
          Failure.newBuilder()
              .setStackTrace(stackTrace)
              .setCanceledFailureInfo(CanceledFailureInfo.newBuilder())
              .build());
    }
    if (exception instanceof Error) {
      if (isLocalActivity) {
        metricsScope.counter(MetricsType.LOCAL_ACTIVITY_ERROR_COUNTER).inc(1);
      } else {
        metricsScope.counter(MetricsType.ACTIVITY_TASK_ERROR_COUNTER).inc(1);
      }
      throw (Error) exception;
    }

    if (isLocalActivity) {
      metricsScope.counter(MetricsType.LOCAL_ACTIVITY_FAILED_COUNTER).inc(1);
    } else {
      metricsScope.counter(MetricsType.ACTIVITY_EXEC_FAILED_COUNTER).inc(1);
    }
    if (exception instanceof TemporalFailure) {
      ((TemporalFailure) exception).setDataConverter(dataConverter);
    }
    if (exception instanceof TimeoutFailure) {
      exception = new SimulatedTimeoutFailure((TimeoutFailure) exception);
    }
    Failure failure = FailureConverter.exceptionToFailure(exception);
    RespondActivityTaskFailedRequest.Builder result =
        RespondActivityTaskFailedRequest.newBuilder().setFailure(failure);
    return new ActivityTaskHandler.Result(
        activityId, null, new Result.TaskFailedResult(result.build(), exception), null, null);
  }

  @Override
  public boolean isAnyTypeSupported() {
    return !activities.isEmpty();
  }

  @VisibleForTesting
  public Set<String> getRegisteredActivityTypes() {
    return activities.keySet();
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
      PollActivityTaskQueueResponse pollResponse, Scope metricsScope, boolean localActivity) {
    String activityType = pollResponse.getActivityType().getName();
    ActivityInfoImpl activityTask =
        new ActivityInfoImpl(pollResponse, this.namespace, localActivity);
    ActivityTaskExecutor activity = activities.get(activityType);
    if (activity == null) {
      String knownTypes = Joiner.on(", ").join(activities.keySet());
      return mapToActivityFailure(
          new IllegalArgumentException(
              "Activity Type \""
                  + activityType
                  + "\" is not registered with a worker. Known types are: "
                  + knownTypes),
          pollResponse.getActivityId(),
          metricsScope,
          localActivity);
    }
    return activity.execute(activityTask, metricsScope);
  }

  interface ActivityTaskExecutor {
    ActivityTaskHandler.Result execute(ActivityInfoImpl task, Scope metricsScope);
  }

  private class POJOActivityImplementation implements ActivityTaskExecutor {
    private final Method method;
    private final Object activity;

    POJOActivityImplementation(Method interfaceMethod, Object activity) {
      this.method = interfaceMethod;
      this.activity = activity;
    }

    @Override
    public ActivityTaskHandler.Result execute(ActivityInfoImpl info, Scope metricsScope) {
      ActivityExecutionContext context =
          new ActivityExecutionContextImpl(
              service, namespace, info, dataConverter, heartbeatExecutor, metricsScope);
      Optional<Payloads> input = info.getInput();
      ActivityInboundCallsInterceptor inboundCallsInterceptor =
          new POJOActivityInboundCallsInterceptor(activity, method);
      for (ActivityInterceptor interceptor : interceptors) {
        inboundCallsInterceptor = interceptor.interceptActivity(inboundCallsInterceptor);
      }
      inboundCallsInterceptor.init(context);
      try {
        Object[] args =
            DataConverter.arrayFromPayloads(
                dataConverter,
                input,
                method.getParameterTypes(),
                method.getGenericParameterTypes());
        Object result = inboundCallsInterceptor.execute(args);
        if (context.isDoNotCompleteOnReturn()) {
          return new ActivityTaskHandler.Result(info.getActivityId(), null, null, null, null);
        }
        RespondActivityTaskCompletedRequest.Builder request =
            RespondActivityTaskCompletedRequest.newBuilder();
        if (method.getReturnType() != Void.TYPE) {
          Optional<Payloads> serialized = dataConverter.toPayloads(result);
          if (serialized.isPresent()) {
            request.setResult(serialized.get());
          }
        }
        return new ActivityTaskHandler.Result(
            info.getActivityId(), request.build(), null, null, null);
      } catch (Throwable e) {
        return mapToActivityFailure(e, info.getActivityId(), metricsScope, false);
      }
    }
  }

  private static class POJOActivityInboundCallsInterceptor
      implements ActivityInboundCallsInterceptor {
    private final Object activity;
    private final Method method;
    private ActivityExecutionContext context;

    private POJOActivityInboundCallsInterceptor(Object activity, Method method) {
      this.activity = activity;
      this.method = method;
    }

    @Override
    public void init(ActivityExecutionContext context) {
      this.context = context;
    }

    @Override
    public Object execute(Object[] arguments) {
      CurrentActivityExecutionContext.set(context);
      try {
        return method.invoke(activity, arguments);
      } catch (Error e) {
        throw e;
      } catch (InvocationTargetException e) {
        throw Activity.wrap((Exception) e.getTargetException());
      } catch (Exception e) {
        throw Activity.wrap(e);
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
    public ActivityTaskHandler.Result execute(ActivityInfoImpl info, Scope metricsScope) {
      ActivityExecutionContext context = new LocalActivityExecutionContextImpl(info, metricsScope);
      Optional<Payloads> input = info.getInput();
      ActivityInboundCallsInterceptor inboundCallsInterceptor =
          new POJOActivityInboundCallsInterceptor(activity, method);
      for (ActivityInterceptor interceptor : interceptors) {
        inboundCallsInterceptor = interceptor.interceptActivity(inboundCallsInterceptor);
      }
      inboundCallsInterceptor.init(context);
      try {
        Object[] args =
            DataConverter.arrayFromPayloads(
                dataConverter,
                input,
                method.getParameterTypes(),
                method.getGenericParameterTypes());
        Object result = inboundCallsInterceptor.execute(args);
        RespondActivityTaskCompletedRequest.Builder request =
            RespondActivityTaskCompletedRequest.newBuilder();
        if (method.getReturnType() != Void.TYPE) {
          Optional<Payloads> serialized = dataConverter.toPayloads(result);
          if (serialized.isPresent()) {
            request.setResult(serialized.get());
          }
        }
        return new ActivityTaskHandler.Result(
            info.getActivityId(), request.build(), null, null, null);
      } catch (Throwable e) {
        return mapToActivityFailure(e, info.getActivityId(), metricsScope, false);
      }
    }
  }
}
