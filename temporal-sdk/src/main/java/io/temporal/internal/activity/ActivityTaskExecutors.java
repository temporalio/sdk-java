package io.temporal.internal.activity;

import static io.temporal.internal.activity.ActivityTaskHandlerImpl.mapToActivityFailure;

import com.uber.m3.tally.Scope;
import io.temporal.activity.ActivityInfo;
import io.temporal.activity.DynamicActivity;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedRequest;
import io.temporal.client.ActivityCanceledException;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.EncodedValues;
import io.temporal.common.interceptors.ActivityInboundCallsInterceptor;
import io.temporal.common.interceptors.ActivityInboundCallsInterceptor.ActivityOutput;
import io.temporal.common.interceptors.Header;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.internal.common.FailureUtils;
import io.temporal.internal.worker.ActivityTaskHandler;
import io.temporal.payload.context.ActivitySerializationContext;
import io.temporal.serviceclient.CheckedExceptionWrapper;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ActivityTaskExecutors {
  static final Logger log = LoggerFactory.getLogger(ActivityTaskExecutor.class);

  interface ActivityTaskExecutor {
    ActivityTaskHandler.Result execute(ActivityInfoInternal info, Scope metricsScope);
  }

  abstract static class BaseActivityTaskExecutor implements ActivityTaskExecutor {
    private final DataConverter dataConverter;
    private final List<ContextPropagator> contextPropagators;
    private final WorkerInterceptor[] interceptors;
    private final ActivityExecutionContextFactory executionContextFactory;

    public BaseActivityTaskExecutor(
        DataConverter dataConverter,
        List<ContextPropagator> contextPropagators,
        WorkerInterceptor[] interceptors,
        ActivityExecutionContextFactory executionContextFactory) {
      this.dataConverter = dataConverter;
      this.contextPropagators = contextPropagators;
      this.interceptors = interceptors;
      this.executionContextFactory = executionContextFactory;
    }

    @Override
    public ActivityTaskHandler.Result execute(ActivityInfoInternal info, Scope metricsScope) {
      InternalActivityExecutionContext context =
          executionContextFactory.createContext(info, getActivity(), metricsScope);
      ActivityInfo activityInfo = context.getInfo();
      ActivitySerializationContext serializationContext =
          new ActivitySerializationContext(
              activityInfo.getNamespace(),
              activityInfo.getWorkflowId(),
              activityInfo.getWorkflowType(),
              activityInfo.getActivityType(),
              activityInfo.getActivityTaskQueue(),
              activityInfo.isLocal());
      DataConverter dataConverterWithActivityContext =
          dataConverter.withContext(serializationContext);

      try {
        info.getHeader()
            .ifPresent(value -> deserializeAndPopulateContext(value, contextPropagators));

        ActivityInboundCallsInterceptor inboundCallsInterceptor = createRootInboundInterceptor();
        for (WorkerInterceptor interceptor : interceptors) {
          inboundCallsInterceptor = interceptor.interceptActivity(inboundCallsInterceptor);
        }
        inboundCallsInterceptor.init(context);

        Object[] args = provideArgs(info.getInput(), dataConverterWithActivityContext);
        Header header =
            new Header(
                info.getHeader().orElse(io.temporal.api.common.v1.Header.getDefaultInstance()));
        ActivityOutput result =
            inboundCallsInterceptor.execute(
                new ActivityInboundCallsInterceptor.ActivityInput(header, args));
        if (context.isDoNotCompleteOnReturn()) {
          return new ActivityTaskHandler.Result(
              info.getActivityId(), null, null, null, context.isUseLocalManualCompletion());
        }

        return this.constructSuccessfulResultValue(info, result, dataConverterWithActivityContext);
      } catch (Throwable e) {
        Throwable ex = CheckedExceptionWrapper.unwrap(e);
        boolean local = info.isLocal();
        if (ex instanceof ActivityCanceledException) {
          log.info(
              "{} canceled. ActivityId={}, activityType={}, attempt={}",
              local ? "Local activity" : "Activity",
              info.getActivityId(),
              info.getActivityType(),
              info.getAttempt());
        } else if (FailureUtils.isBenignApplicationFailure(ex)) {
          log.debug(
              "{} failure. ActivityId={}, activityType={}, attempt={}",
              local ? "Local activity" : "Activity",
              info.getActivityId(),
              info.getActivityType(),
              info.getAttempt(),
              ex);
        } else {
          log.warn(
              "{} failure. ActivityId={}, activityType={}, attempt={}",
              local ? "Local activity" : "Activity",
              info.getActivityId(),
              info.getActivityType(),
              info.getAttempt(),
              ex);
        }

        return mapToActivityFailure(
            ex,
            info.getActivityId(),
            context.getLastHeartbeatValue(),
            metricsScope,
            local,
            dataConverterWithActivityContext);
      } finally {
        if (!context.isDoNotCompleteOnReturn()) {
          // if the activity is not completed, we need to cancel the heartbeat
          // to avoid sending it after the activity is completed
          context.cancelOutstandingHeartbeat();
        }
      }
    }

    abstract ActivityInboundCallsInterceptor createRootInboundInterceptor();

    abstract Object getActivity();

    abstract Object[] provideArgs(
        Optional<Payloads> input, DataConverter dataConverterWithActivityContext);

    protected abstract ActivityTaskHandler.Result constructSuccessfulResultValue(
        ActivityInfoInternal info,
        ActivityOutput result,
        DataConverter dataConverterWithActivityContext);

    ActivityTaskHandler.Result constructResultValue(
        ActivityInfoInternal info,
        @Nullable ActivityOutput result,
        DataConverter dataConverterWithActivityContext) {
      RespondActivityTaskCompletedRequest.Builder request =
          RespondActivityTaskCompletedRequest.newBuilder();
      if (result != null) {
        Optional<Payloads> serialized =
            dataConverterWithActivityContext.toPayloads(result.getResult());
        serialized.ifPresent(request::setResult);
      }
      return new ActivityTaskHandler.Result(
          info.getActivityId(), request.build(), null, null, false);
    }

    static void deserializeAndPopulateContext(
        @Nonnull io.temporal.api.common.v1.Header header,
        @Nullable List<ContextPropagator> contextPropagatorList) {
      if (contextPropagatorList == null || contextPropagatorList.isEmpty()) {
        return;
      }

      Map<String, Payload> headerData = new HashMap<>(header.getFieldsMap());
      for (ContextPropagator propagator : contextPropagatorList) {
        propagator.setCurrentContext(propagator.deserializeContext(headerData));
      }
    }
  }

  static class POJOActivityImplementation extends BaseActivityTaskExecutor {
    private final Method method;
    private final Object activity;

    POJOActivityImplementation(
        Method interfaceMethod,
        Object activity,
        DataConverter dataConverter,
        List<ContextPropagator> contextPropagators,
        WorkerInterceptor[] interceptors,
        ActivityExecutionContextFactory executionContextFactory) {
      super(dataConverter, contextPropagators, interceptors, executionContextFactory);
      this.method = interfaceMethod;
      this.activity = activity;
    }

    @Override
    ActivityInboundCallsInterceptor createRootInboundInterceptor() {
      return new RootActivityInboundCallsInterceptor.POJOActivityInboundCallsInterceptor(
          activity, method);
    }

    @Override
    Object getActivity() {
      return activity;
    }

    @Override
    Object[] provideArgs(Optional<Payloads> input, DataConverter dataConverterWithActivityContext) {
      return dataConverterWithActivityContext.fromPayloads(
          input, method.getParameterTypes(), method.getGenericParameterTypes());
    }

    @Override
    protected ActivityTaskHandler.Result constructSuccessfulResultValue(
        ActivityInfoInternal info,
        ActivityOutput result,
        DataConverter dataConverterWithActivityContext) {
      return constructResultValue(
          info,
          // if the expected result of the method is null, we don't publish result at all
          method.getReturnType() != Void.TYPE ? result : null,
          dataConverterWithActivityContext);
    }
  }

  static class DynamicActivityImplementation extends BaseActivityTaskExecutor {
    private final DynamicActivity activity;

    DynamicActivityImplementation(
        DynamicActivity activity,
        DataConverter dataConverter,
        List<ContextPropagator> contextPropagators,
        WorkerInterceptor[] interceptors,
        ActivityExecutionContextFactory executionContextFactory) {
      super(dataConverter, contextPropagators, interceptors, executionContextFactory);
      this.activity = activity;
    }

    @Override
    ActivityInboundCallsInterceptor createRootInboundInterceptor() {
      return new RootActivityInboundCallsInterceptor.DynamicActivityInboundCallsInterceptor(
          activity);
    }

    @Override
    Object getActivity() {
      return activity;
    }

    @Override
    Object[] provideArgs(Optional<Payloads> input, DataConverter dataConverterWithActivityContext) {
      EncodedValues encodedValues = new EncodedValues(input, dataConverterWithActivityContext);
      return new Object[] {encodedValues};
    }

    @Override
    protected ActivityTaskHandler.Result constructSuccessfulResultValue(
        ActivityInfoInternal info,
        ActivityOutput result,
        DataConverter dataConverterWithActivityContext) {
      return constructResultValue(info, result, dataConverterWithActivityContext);
    }
  }
}
