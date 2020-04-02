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

import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.uber.m3.tally.Scope;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.client.ActivityCancelledException;
import io.temporal.common.MethodRetry;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.common.CheckedExceptionWrapper;
import io.temporal.internal.common.InternalUtils;
import io.temporal.internal.common.OptionsUtils;
import io.temporal.internal.metrics.MetricsType;
import io.temporal.internal.worker.ActivityTaskHandler;
import io.temporal.proto.workflowservice.PollForActivityTaskResponse;
import io.temporal.proto.workflowservice.RespondActivityTaskCompletedRequest;
import io.temporal.proto.workflowservice.RespondActivityTaskFailedRequest;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.testing.SimulatedTimeoutException;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiFunction;

class POJOActivityTaskHandler implements ActivityTaskHandler {

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
    Set<MethodInterfacePair> activityMethods =
        getAnnotatedInterfaceMethods(cls, ActivityInterface.class);
    if (activityMethods.isEmpty()) {
      throw new IllegalArgumentException(
          "Class doesn't implement any non empty interface annotated with @ActivityInterface: "
              + cls.getName());
    }
    for (MethodInterfacePair pair : activityMethods) {
      Method method = pair.getMethod();
      ActivityMethod annotation = method.getAnnotation(ActivityMethod.class);
      String activityType;
      if (annotation != null && !annotation.name().isEmpty()) {
        activityType = annotation.name();
      } else {
        activityType = InternalUtils.getSimpleName(pair.getType(), method);
      }
      if (activities.containsKey(activityType)) {
        throw new IllegalStateException(
            activityType + " activity type is already registered with the worker");
      }

      ActivityTaskExecutor implementation = newTaskExecutor.apply(method, activity);
      activities.put(activityType, implementation);
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

    failure = CheckedExceptionWrapper.unwrap(failure);
    RespondActivityTaskFailedRequest result =
        RespondActivityTaskFailedRequest.newBuilder()
            .setReason(failure.getClass().getName())
            .setDetails(OptionsUtils.toByteString(dataConverter.toData(failure)))
            .build();
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
          new ActivityExecutionContextImpl(
              service, namespace, task, dataConverter, heartbeatExecutor);
      byte[] input = task.getInput();
      CurrentActivityExecutionContext.set(context);
      try {
        Object[] args = dataConverter.fromDataArray(input, method.getGenericParameterTypes());
        Object result = method.invoke(activity, args);
        if (context.isDoNotCompleteOnReturn()) {
          return new ActivityTaskHandler.Result(null, null, null, null);
        }
        RespondActivityTaskCompletedRequest.Builder request =
            RespondActivityTaskCompletedRequest.newBuilder();
        if (method.getReturnType() != Void.TYPE) {
          request.setResult(OptionsUtils.toByteString(dataConverter.toData(result)));
        }
        return new ActivityTaskHandler.Result(request.build(), null, null, null);
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
          new LocalActivityExecutionContextImpl(service, namespace, task);
      CurrentActivityExecutionContext.set(context);
      byte[] input = task.getInput();
      try {
        Object[] args = dataConverter.fromDataArray(input, method.getGenericParameterTypes());
        Object result = method.invoke(activity, args);
        RespondActivityTaskCompletedRequest.Builder request =
            RespondActivityTaskCompletedRequest.newBuilder();
        if (method.getReturnType() != Void.TYPE) {
          request.setResult(OptionsUtils.toByteString(dataConverter.toData(result)));
        }
        return new ActivityTaskHandler.Result(request.build(), null, null, null);
      } catch (RuntimeException | IllegalAccessException e) {
        return mapToActivityFailure(e, metricsScope, true);
      } catch (InvocationTargetException e) {
        return mapToActivityFailure(e.getTargetException(), metricsScope, true);
      } finally {
        CurrentActivityExecutionContext.unset();
      }
    }
  }

  static class MethodInterfacePair {
    private final Method method;
    private final Class<?> type;

    MethodInterfacePair(Method method, Class<?> type) {
      this.method = method;
      this.type = type;
    }

    public Method getMethod() {
      return method;
    }

    public Class<?> getType() {
      return type;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      MethodInterfacePair that = (MethodInterfacePair) o;
      return Objects.equal(method, that.method) && Objects.equal(type, that.type);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(method, type);
    }

    @Override
    public String toString() {
      return "MethodInterfacePair{" + "method=" + method + ", type=" + type + '}';
    }
  }

  /** Used to override equals and hashCode of Method to ensure deduping by method name in a set. */
  static class MethodWrapper {
    private final Method method;

    MethodWrapper(Method method) {
      this.method = method;
    }

    public Method getMethod() {
      return method;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      MethodWrapper that = (MethodWrapper) o;
      return Objects.equal(method.getName(), that.method.getName());
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(method.getName());
    }
  }

  Set<MethodInterfacePair> getAnnotatedInterfaceMethods(
      Class<?> implementationClass, Class<? extends Annotation> annotationClass) {
    if (implementationClass.isInterface()) {
      throw new IllegalArgumentException(
          "Concrete class expected. Found interface: " + implementationClass.getSimpleName());
    }
    Set<MethodInterfacePair> pairs = new HashSet<>();
    // Methods inherited from interfaces that are not annotated with @ActivityInterface
    Set<MethodWrapper> ignored = new HashSet<>();
    getAnnotatedInterfaceMethods(implementationClass, annotationClass, ignored, pairs);
    return pairs;
  }

  private void getAnnotatedInterfaceMethods(
      Class<?> current,
      Class<? extends Annotation> annotationClass,
      Set<MethodWrapper> methods,
      Set<MethodInterfacePair> result) {
    // Using set to dedupe methods which are defined in both non activity parent and current
    Set<MethodWrapper> ourMethods = new HashSet<>();
    if (current.isInterface()) {
      Method[] declaredMethods = current.getDeclaredMethods();
      for (int i = 0; i < declaredMethods.length; i++) {
        Method declaredMethod = declaredMethods[i];
        ourMethods.add(new MethodWrapper(declaredMethod));
      }
    }
    Class<?>[] interfaces = current.getInterfaces();
    for (int i = 0; i < interfaces.length; i++) {
      Class<?> anInterface = interfaces[i];
      getAnnotatedInterfaceMethods(anInterface, annotationClass, ourMethods, result);
    }
    Annotation annotation = current.getAnnotation(annotationClass);
    if (annotation == null) {
      methods.addAll(ourMethods);
      return;
    }
    for (MethodWrapper method : ourMethods) {
      result.add(new MethodInterfacePair(method.getMethod(), current));
    }
  }
}
