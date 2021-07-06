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
import com.uber.m3.util.ImmutableMap;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.activity.DynamicActivity;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.failure.v1.CanceledFailureInfo;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
import io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedRequest;
import io.temporal.api.workflowservice.v1.RespondActivityTaskFailedRequest;
import io.temporal.client.ActivityCanceledException;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.EncodedValues;
import io.temporal.common.interceptors.ActivityInboundCallsInterceptor;
import io.temporal.common.interceptors.Header;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.common.metadata.POJOActivityImplMetadata;
import io.temporal.common.metadata.POJOActivityInterfaceMetadata;
import io.temporal.common.metadata.POJOActivityMethodMetadata;
import io.temporal.failure.FailureConverter;
import io.temporal.failure.SimulatedTimeoutFailure;
import io.temporal.failure.TemporalFailure;
import io.temporal.failure.TimeoutFailure;
import io.temporal.internal.metrics.MetricsType;
import io.temporal.internal.replay.FailureWrapperException;
import io.temporal.internal.worker.ActivityTask;
import io.temporal.internal.worker.ActivityTaskHandler;
import io.temporal.serviceclient.CheckedExceptionWrapper;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.serviceclient.WorkflowServiceStubs;
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

@VisibleForTesting
public final class POJOActivityTaskHandler implements ActivityTaskHandler {

  private static final Logger log = LoggerFactory.getLogger(POJOActivityTaskHandler.class);

  private final DataConverter dataConverter;
  private final ScheduledExecutorService heartbeatExecutor;
  private final WorkflowServiceStubs service;
  private final String namespace;
  private final WorkerInterceptor[] interceptors;
  private final Map<String, ActivityTaskExecutor> activities =
      Collections.synchronizedMap(new HashMap<>());
  private ActivityTaskExecutor dynamicActivity;

  @VisibleForTesting
  public POJOActivityTaskHandler(
      WorkflowServiceStubs service,
      String namespace,
      DataConverter dataConverter,
      ScheduledExecutorService heartbeatExecutor,
      WorkerInterceptor[] interceptors) {
    this.service = Objects.requireNonNull(service);
    this.namespace = Objects.requireNonNull(namespace);
    this.dataConverter = Objects.requireNonNull(dataConverter);
    this.heartbeatExecutor = Objects.requireNonNull(heartbeatExecutor);
    this.interceptors = Objects.requireNonNull(interceptors);
  }

  private void registerActivityImplementation(
      Object activity, BiFunction<Method, Object, ActivityTaskExecutor> newTaskExecutor) {
    if (activity instanceof Class) {
      throw new IllegalArgumentException("Activity object instance expected, not the class");
    }
    if (activity instanceof DynamicActivity) {
      if (dynamicActivity != null) {
        throw new IllegalStateException(
            "An implementation of DynamicActivity is already registered with the worker");
      }
      dynamicActivity = new DynamicActivityImplementation((DynamicActivity) activity);
      return;
    }
    Class<?> cls = activity.getClass();
    POJOActivityImplMetadata activityImplMetadata = POJOActivityImplMetadata.newInstance(cls);
    for (POJOActivityInterfaceMetadata activityInterface :
        activityImplMetadata.getActivityInterfaces()) {
      for (POJOActivityMethodMetadata activityMetadata : activityInterface.getMethodsMetadata()) {
        String typeName = activityMetadata.getActivityTypeName();
        if (activities.containsKey(typeName)) {
          throw new IllegalArgumentException(
              "\"" + typeName + "\" activity type is already registered with the worker");
        }
        Method method = activityMetadata.getMethod();
        ActivityTaskExecutor implementation = newTaskExecutor.apply(method, activity);
        activities.put(typeName, implementation);
      }
    }
  }

  private ActivityTaskHandler.Result mapToActivityFailure(
      Throwable exception, String activityId, Scope metricsScope, boolean isLocalActivity) {
    if (exception instanceof ActivityCanceledException) {
      if (isLocalActivity) {
        metricsScope.counter(MetricsType.LOCAL_ACTIVITY_CANCELED_COUNTER).inc(1);
      } else {
        metricsScope.counter(MetricsType.ACTIVITY_CANCELED_COUNTER).inc(1);
      }
      String stackTrace = FailureConverter.serializeStackTrace(exception);
      throw new FailureWrapperException(
          Failure.newBuilder()
              .setStackTrace(stackTrace)
              .setCanceledFailureInfo(CanceledFailureInfo.newBuilder())
              .build());
    }
    Scope ms =
        metricsScope.tagged(
            ImmutableMap.of(MetricsTag.EXCEPTION, exception.getClass().getSimpleName()));
    if (isLocalActivity) {
      ms.counter(MetricsType.LOCAL_ACTIVITY_FAILED_COUNTER).inc(1);
    } else {
      ms.counter(MetricsType.ACTIVITY_EXEC_FAILED_COUNTER).inc(1);
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
        activityId,
        null,
        new Result.TaskFailedResult(result.build(), exception),
        null,
        null,
        false);
  }

  @Override
  public boolean isAnyTypeSupported() {
    return !activities.isEmpty() || dynamicActivity != null;
  }

  @VisibleForTesting
  public Set<String> getRegisteredActivityTypes() {
    return activities.keySet();
  }

  public void registerActivityImplementations(Object[] activitiesImplementation) {
    for (Object activity : activitiesImplementation) {
      registerActivityImplementation(activity, POJOActivityImplementation::new);
    }
  }

  void registerLocalActivityImplementations(Object[] activitiesImplementation) {
    for (Object activity : activitiesImplementation) {
      registerActivityImplementation(activity, POJOLocalActivityImplementation::new);
    }
  }

  @Override
  public Result handle(ActivityTask activityTask, Scope metricsScope, boolean localActivity) {
    PollActivityTaskQueueResponse pollResponse = activityTask.getResponse();
    String activityType = pollResponse.getActivityType().getName();
    ActivityInfoInternal activityInfo =
        new ActivityInfoImpl(
            pollResponse, this.namespace, localActivity, activityTask.getCompletionHandle());
    ActivityTaskExecutor activity = activities.get(activityType);
    if (activity == null) {
      if (dynamicActivity != null) {
        return dynamicActivity.execute(activityInfo, metricsScope);
      }
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
    return activity.execute(activityInfo, metricsScope);
  }

  private interface ActivityTaskExecutor {
    ActivityTaskHandler.Result execute(ActivityInfoInternal task, Scope metricsScope);
  }

  private class POJOActivityImplementation implements ActivityTaskExecutor {
    private final Method method;
    private final Object activity;

    POJOActivityImplementation(Method interfaceMethod, Object activity) {
      this.method = interfaceMethod;
      this.activity = activity;
    }

    @Override
    public ActivityTaskHandler.Result execute(ActivityInfoInternal info, Scope metricsScope) {
      ActivityExecutionContext context =
          new ActivityExecutionContextImpl(
              service,
              namespace,
              info,
              dataConverter,
              heartbeatExecutor,
              info.getCompletionHandle(),
              metricsScope);
      Optional<Payloads> input = info.getInput();
      ActivityInboundCallsInterceptor inboundCallsInterceptor =
          new POJOActivityInboundCallsInterceptor(activity, method);
      for (WorkerInterceptor interceptor : interceptors) {
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
        ActivityInboundCallsInterceptor.ActivityOutput result =
            inboundCallsInterceptor.execute(
                new ActivityInboundCallsInterceptor.ActivityInput(
                    new Header(info.getHeader()), args));
        if (context.isDoNotCompleteOnReturn()) {
          return new ActivityTaskHandler.Result(
              info.getActivityId(), null, null, null, null, context.isUseLocalManualCompletion());
        }
        RespondActivityTaskCompletedRequest.Builder request =
            RespondActivityTaskCompletedRequest.newBuilder();
        if (method.getReturnType() != Void.TYPE) {
          Optional<Payloads> serialized = dataConverter.toPayloads(result.getResult());
          if (serialized.isPresent()) {
            request.setResult(serialized.get());
          }
        }
        return new ActivityTaskHandler.Result(
            info.getActivityId(), request.build(), null, null, null, false);
      } catch (Throwable e) {
        return activityFailureToResult(info, metricsScope, e);
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
    public ActivityOutput execute(ActivityInput input) {
      CurrentActivityExecutionContext.set(context);
      try {
        Object result = method.invoke(activity, input.getArguments());
        return new ActivityOutput(result);
      } catch (InvocationTargetException e) {
        throw Activity.wrap(e.getTargetException());
      } catch (Exception e) {
        throw Activity.wrap(e);
      } finally {
        CurrentActivityExecutionContext.unset();
      }
    }
  }

  private class DynamicActivityImplementation implements ActivityTaskExecutor {

    private final DynamicActivity activity;

    public DynamicActivityImplementation(DynamicActivity activity) {
      this.activity = activity;
    }

    @Override
    public ActivityTaskHandler.Result execute(ActivityInfoInternal info, Scope metricsScope) {
      ActivityExecutionContext context =
          new ActivityExecutionContextImpl(
              service,
              namespace,
              info,
              dataConverter,
              heartbeatExecutor,
              info.getCompletionHandle(),
              metricsScope);
      Optional<Payloads> input = info.getInput();
      ActivityInboundCallsInterceptor inboundCallsInterceptor =
          new DynamicActivityInboundCallsInterceptor(activity);
      for (WorkerInterceptor interceptor : interceptors) {
        inboundCallsInterceptor = interceptor.interceptActivity(inboundCallsInterceptor);
      }
      inboundCallsInterceptor.init(context);
      try {
        EncodedValues encodedValues = new EncodedValues(input, dataConverter);
        Object[] args = new Object[] {encodedValues};

        ActivityInboundCallsInterceptor.ActivityOutput result =
            inboundCallsInterceptor.execute(
                new ActivityInboundCallsInterceptor.ActivityInput(
                    new Header(info.getHeader()), args));
        if (context.isDoNotCompleteOnReturn()) {
          return new ActivityTaskHandler.Result(
              info.getActivityId(), null, null, null, null, context.isUseLocalManualCompletion());
        }
        RespondActivityTaskCompletedRequest.Builder request =
            RespondActivityTaskCompletedRequest.newBuilder();
        Optional<Payloads> serialized = dataConverter.toPayloads(result.getResult());
        if (serialized.isPresent()) {
          request.setResult(serialized.get());
        }
        return new ActivityTaskHandler.Result(
            info.getActivityId(), request.build(), null, null, null, false);
      } catch (Throwable e) {
        return activityFailureToResult(info, metricsScope, e);
      }
    }
  }

  private Result activityFailureToResult(
      ActivityInfoInternal info, Scope metricsScope, Throwable e) {
    e = CheckedExceptionWrapper.unwrap(e);
    if (e instanceof ActivityCanceledException) {
      if (log.isInfoEnabled()) {
        log.info(
            "Activity canceled. ActivityId="
                + info.getActivityId()
                + ", activityType="
                + info.getActivityType()
                + ", attempt="
                + info.getAttempt());
      }
    } else if (log.isWarnEnabled()) {
      log.warn(
          "Activity failure. ActivityId="
              + info.getActivityId()
              + ", activityType="
              + info.getActivityType()
              + ", attempt="
              + info.getAttempt(),
          e);
    }
    return mapToActivityFailure(e, info.getActivityId(), metricsScope, info.isLocal());
  }

  private static class DynamicActivityInboundCallsInterceptor
      implements ActivityInboundCallsInterceptor {
    private final DynamicActivity activity;
    private ActivityExecutionContext context;

    private DynamicActivityInboundCallsInterceptor(DynamicActivity activity) {
      this.activity = activity;
    }

    @Override
    public void init(ActivityExecutionContext context) {
      this.context = context;
    }

    @Override
    public ActivityOutput execute(ActivityInput input) {
      CurrentActivityExecutionContext.set(context);
      try {
        Object result = activity.execute((EncodedValues) input.getArguments()[0]);
        return new ActivityOutput(result);
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
    public ActivityTaskHandler.Result execute(ActivityInfoInternal info, Scope metricsScope) {
      ActivityExecutionContext context = new LocalActivityExecutionContextImpl(info, metricsScope);
      Optional<Payloads> input = info.getInput();
      ActivityInboundCallsInterceptor inboundCallsInterceptor =
          new POJOActivityInboundCallsInterceptor(activity, method);
      for (WorkerInterceptor interceptor : interceptors) {
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
        ActivityInboundCallsInterceptor.ActivityOutput result =
            inboundCallsInterceptor.execute(
                new ActivityInboundCallsInterceptor.ActivityInput(
                    new Header(info.getHeader()), args));
        RespondActivityTaskCompletedRequest.Builder request =
            RespondActivityTaskCompletedRequest.newBuilder();
        if (method.getReturnType() != Void.TYPE) {
          Optional<Payloads> serialized = dataConverter.toPayloads(result.getResult());
          if (serialized.isPresent()) {
            request.setResult(serialized.get());
          }
        }
        return new ActivityTaskHandler.Result(
            info.getActivityId(), request.build(), null, null, null, false);
      } catch (Throwable e) {
        e = CheckedExceptionWrapper.unwrap(e);
        if (log.isWarnEnabled()) {
          log.warn(
              "Local activity failure. ActivityId="
                  + info.getActivityId()
                  + ", activityType="
                  + info.getActivityType()
                  + ", attempt="
                  + info.getAttempt(),
              e);
        }
        return mapToActivityFailure(e, info.getActivityId(), metricsScope, info.isLocal());
      }
    }
  }
}
