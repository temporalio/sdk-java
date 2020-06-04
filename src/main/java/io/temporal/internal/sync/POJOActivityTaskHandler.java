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
import io.temporal.client.ActivityCancelledException;
import io.temporal.common.converter.DataConverter;
import io.temporal.failure.FailureConverter;
import io.temporal.internal.metrics.MetricsType;
import io.temporal.internal.worker.ActivityTaskHandler;
import io.temporal.proto.common.ActivityType;
import io.temporal.proto.common.Payloads;
import io.temporal.proto.failure.Failure;
import io.temporal.proto.workflowservice.PollForActivityTaskResponse;
import io.temporal.proto.workflowservice.RespondActivityTaskCompletedRequest;
import io.temporal.proto.workflowservice.RespondActivityTaskFailedRequest;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.testing.SimulatedTimeoutException;
import io.temporal.workflow.ActivityTimeoutException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
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
  private WorkflowServiceStubs service;
  private final String namespace;

  POJOActivityTaskHandler(
      WorkflowServiceStubs service,
      String namespace,
      DataConverter dataConverter,
      ScheduledExecutorService heartbeatExecutor) {
    this.service = service;
    this.namespace = namespace;
    this.dataConverter = dataConverter;
    this.heartbeatExecutor = heartbeatExecutor;
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
      Throwable exception,
      String activityType,
      String activityId,
      Scope metricsScope,
      boolean isLocalActivity) {

    if (exception instanceof ActivityCancelledException) {
      if (isLocalActivity) {
        metricsScope.counter(MetricsType.LOCAL_ACTIVITY_CANCELED_COUNTER).inc(1);
      }
      throw new CancellationException(exception.getMessage());
    }

    // Only expected during unit tests.
    if (exception instanceof SimulatedTimeoutException) {
      SimulatedTimeoutException timeoutException = (SimulatedTimeoutException) exception;
      Object d = timeoutException.getDetails();
      Optional<Payloads> payloads = dataConverter.toData(d);
      ActivityTimeoutException at =
          new ActivityTimeoutException(
              0,
              ActivityType.newBuilder().setName(activityType).build(),
              activityId,
              timeoutException.getTimeoutType(),
              payloads,
              dataConverter);
      exception = new SimulatedTimeoutExceptionInternal(at);
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

    Failure failure = FailureConverter.exceptionToFailure(exception, dataConverter);
    RespondActivityTaskFailedRequest.Builder result =
        RespondActivityTaskFailedRequest.newBuilder().setFailure(failure);
    return new ActivityTaskHandler.Result(
        null, new Result.TaskFailedResult(result.build(), exception), null, null);
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
          activityType,
          pollResponse.getActivityId(),
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
          new ActivityExecutionContextImpl(
              service, namespace, task, dataConverter, heartbeatExecutor);
      Optional<Payloads> input = task.getInput();
      CurrentActivityExecutionContext.set(context);
      try {
        Object[] args =
            dataConverter.fromDataArray(
                input, method.getParameterTypes(), method.getGenericParameterTypes());
        Object result = method.invoke(activity, args);
        if (context.isDoNotCompleteOnReturn()) {
          return new ActivityTaskHandler.Result(null, null, null, null);
        }
        RespondActivityTaskCompletedRequest.Builder request =
            RespondActivityTaskCompletedRequest.newBuilder();
        if (method.getReturnType() != Void.TYPE) {
          Optional<Payloads> serialized = dataConverter.toData(result);
          if (serialized.isPresent()) {
            request.setResult(serialized.get());
          }
        }
        return new ActivityTaskHandler.Result(request.build(), null, null, null);
      } catch (RuntimeException | IllegalAccessException e) {
        return mapToActivityFailure(
            e, task.getActivityType(), task.getActivityId(), metricsScope, false);
      } catch (InvocationTargetException e) {
        return mapToActivityFailure(
            e.getTargetException(),
            task.getActivityType(),
            task.getActivityId(),
            metricsScope,
            false);
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
          new LocalActivityExecutionContextImpl(service, namespace, task);
      CurrentActivityExecutionContext.set(context);
      Optional<Payloads> input = task.getInput();
      try {
        Object[] args =
            dataConverter.fromDataArray(
                input, method.getParameterTypes(), method.getGenericParameterTypes());
        Object result = method.invoke(activity, args);
        RespondActivityTaskCompletedRequest.Builder request =
            RespondActivityTaskCompletedRequest.newBuilder();
        if (method.getReturnType() != Void.TYPE) {
          Optional<Payloads> payloads = dataConverter.toData(result);
          if (payloads.isPresent()) {
            request.setResult(payloads.get());
          }
        }
        return new ActivityTaskHandler.Result(request.build(), null, null, null);
      } catch (RuntimeException | IllegalAccessException e) {
        return mapToActivityFailure(
            e, task.getActivityType(), task.getActivityId(), metricsScope, true);
      } catch (InvocationTargetException e) {
        return mapToActivityFailure(
            e.getTargetException(),
            task.getActivityType(),
            task.getActivityId(),
            metricsScope,
            true);
      } finally {
        CurrentActivityExecutionContext.unset();
      }
    }
  }
}
