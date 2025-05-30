package io.temporal.internal.activity;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import com.uber.m3.tally.Scope;
import com.uber.m3.util.ImmutableMap;
import io.temporal.activity.DynamicActivity;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponseOrBuilder;
import io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledRequest;
import io.temporal.api.workflowservice.v1.RespondActivityTaskFailedRequest;
import io.temporal.client.ActivityCanceledException;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.common.metadata.POJOActivityImplMetadata;
import io.temporal.common.metadata.POJOActivityMethodMetadata;
import io.temporal.internal.activity.ActivityTaskExecutors.ActivityTaskExecutor;
import io.temporal.internal.common.FailureUtils;
import io.temporal.internal.common.env.ReflectionUtils;
import io.temporal.internal.worker.ActivityTask;
import io.temporal.internal.worker.ActivityTaskHandler;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.worker.MetricsType;
import io.temporal.worker.TypeAlreadyRegisteredException;
import java.lang.reflect.Method;
import java.util.*;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class ActivityTaskHandlerImpl implements ActivityTaskHandler {
  public static final ImmutableSet<String> ACTIVITY_HANDLER_STACKTRACE_CUTOFF =
      ImmutableSet.<String>builder()
          // POJO
          .add(
              ReflectionUtils.getMethodNameForStackTraceCutoff(
                  ActivityTaskExecutors.POJOActivityImplementation.class,
                  "execute",
                  ActivityInfoInternal.class,
                  Scope.class))
          // Dynamic
          .add(
              ReflectionUtils.getMethodNameForStackTraceCutoff(
                  ActivityTaskExecutors.DynamicActivityImplementation.class,
                  "execute",
                  ActivityInfoInternal.class,
                  Scope.class))
          .build();

  private final DataConverter dataConverter;
  private final String namespace;
  private final String taskQueue;
  private final ActivityExecutionContextFactory executionContextFactory;
  // <ActivityType, Implementation>
  private final Map<String, ActivityTaskExecutor> activities =
      Collections.synchronizedMap(new HashMap<>());
  private ActivityTaskExecutor dynamicActivity;
  private final WorkerInterceptor[] interceptors;
  private final List<ContextPropagator> contextPropagators;

  public ActivityTaskHandlerImpl(
      @Nonnull String namespace,
      @Nonnull String taskQueue,
      @Nonnull DataConverter dataConverter,
      @Nonnull ActivityExecutionContextFactory executionContextFactory,
      @Nonnull WorkerInterceptor[] interceptors,
      @Nullable List<ContextPropagator> contextPropagators) {
    this.namespace = Objects.requireNonNull(namespace);
    this.taskQueue = Objects.requireNonNull(taskQueue);
    this.dataConverter = Objects.requireNonNull(dataConverter);
    this.executionContextFactory = Objects.requireNonNull(executionContextFactory);
    this.interceptors = Objects.requireNonNull(interceptors);
    this.contextPropagators = contextPropagators;
  }

  @Override
  public boolean isAnyTypeSupported() {
    return !activities.isEmpty() || dynamicActivity != null;
  }

  @Override
  public boolean isTypeSupported(String type) {
    return activities.get(type) != null || dynamicActivity != null;
  }

  public void registerActivityImplementations(Object[] activitiesImplementation) {
    for (Object activity : activitiesImplementation) {
      registerActivityImplementation(activity);
    }
  }

  @Override
  public Result handle(ActivityTask activityTask, Scope metricsScope, boolean localActivity) {
    PollActivityTaskQueueResponseOrBuilder pollResponse = activityTask.getResponse();
    String activityType = pollResponse.getActivityType().getName();
    ActivityInfoInternal activityInfo =
        new ActivityInfoImpl(
            pollResponse,
            this.namespace,
            this.taskQueue,
            localActivity,
            activityTask.getCompletionCallback());
    ActivityTaskExecutor activity = activities.get(activityType);
    if (activity != null) {
      return activity.execute(activityInfo, metricsScope);
    }
    if (dynamicActivity != null) {
      return dynamicActivity.execute(activityInfo, metricsScope);
    }

    // unregistered activity
    try {
      String knownTypes = Joiner.on(", ").join(activities.keySet());
      throw new IllegalArgumentException(
          "Activity Type \""
              + activityType
              + "\" is not registered with a worker. Known types are: "
              + knownTypes);
    } catch (Exception exception) {
      return mapToActivityFailure(
          exception,
          pollResponse.getActivityId(),
          null,
          metricsScope,
          localActivity,
          dataConverter);
    }
  }

  private void registerActivityImplementation(Object activity) {
    if (activity instanceof Class) {
      throw new IllegalArgumentException("Activity object instance expected, not the class");
    }
    if (activity instanceof DynamicActivity) {
      if (dynamicActivity != null) {
        throw new TypeAlreadyRegisteredException(
            "DynamicActivity",
            "An implementation of DynamicActivity is already registered with the worker");
      }
      dynamicActivity =
          new ActivityTaskExecutors.DynamicActivityImplementation(
              (DynamicActivity) activity,
              dataConverter,
              contextPropagators,
              interceptors,
              executionContextFactory);
    } else {
      Class<?> cls = activity.getClass();
      POJOActivityImplMetadata activityImplMetadata = POJOActivityImplMetadata.newInstance(cls);
      for (POJOActivityMethodMetadata activityMetadata :
          activityImplMetadata.getActivityMethods()) {
        String typeName = activityMetadata.getActivityTypeName();
        if (activities.containsKey(typeName)) {
          throw new TypeAlreadyRegisteredException(
              typeName, "\"" + typeName + "\" activity type is already registered with the worker");
        }
        Method method = activityMetadata.getMethod();
        ActivityTaskExecutor implementation =
            new ActivityTaskExecutors.POJOActivityImplementation(
                method,
                activity,
                dataConverter,
                contextPropagators,
                interceptors,
                executionContextFactory);
        activities.put(typeName, implementation);
      }
    }
  }

  @SuppressWarnings("deprecation")
  static ActivityTaskHandler.Result mapToActivityFailure(
      Throwable exception,
      String activityId,
      @Nullable Object lastHeartbeatDetails,
      Scope metricsScope,
      boolean isLocalActivity,
      DataConverter dataConverter) {
    if (exception instanceof ActivityCanceledException) {
      if (isLocalActivity) {
        metricsScope.counter(MetricsType.LOCAL_ACTIVITY_EXEC_CANCELLED_COUNTER).inc(1);
        metricsScope.counter(MetricsType.LOCAL_ACTIVITY_CANCELED_COUNTER).inc(1);
      } else {
        metricsScope.counter(MetricsType.ACTIVITY_EXEC_CANCELLED_COUNTER).inc(1);
        metricsScope.counter(MetricsType.ACTIVITY_CANCELED_COUNTER).inc(1);
      }
      return new ActivityTaskHandler.Result(
          activityId, null, null, RespondActivityTaskCanceledRequest.newBuilder().build(), false);
    }
    Scope ms =
        metricsScope.tagged(
            ImmutableMap.of(MetricsTag.EXCEPTION, exception.getClass().getSimpleName()));
    if (!FailureUtils.isBenignApplicationFailure(exception)) {
      if (isLocalActivity) {
        ms.counter(MetricsType.LOCAL_ACTIVITY_EXEC_FAILED_COUNTER).inc(1);
        ms.counter(MetricsType.LOCAL_ACTIVITY_FAILED_COUNTER).inc(1);
      } else {
        ms.counter(MetricsType.ACTIVITY_EXEC_FAILED_COUNTER).inc(1);
      }
    }
    Failure failure = dataConverter.exceptionToFailure(exception);
    RespondActivityTaskFailedRequest.Builder result =
        RespondActivityTaskFailedRequest.newBuilder().setFailure(failure);
    if (lastHeartbeatDetails != null) {
      dataConverter.toPayloads(lastHeartbeatDetails).ifPresent(result::setLastHeartbeatDetails);
    }
    return new ActivityTaskHandler.Result(
        activityId,
        null,
        new ActivityTaskHandler.Result.TaskFailedResult(result.build(), exception),
        null,
        false);
  }
}
