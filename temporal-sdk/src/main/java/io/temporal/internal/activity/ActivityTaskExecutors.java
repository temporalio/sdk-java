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
import io.temporal.internal.common.HeaderUtils;
import io.temporal.internal.worker.ActivityTaskHandler;
import io.temporal.payload.codec.PayloadCodec;
import io.temporal.payload.context.ActivitySerializationContext;
import io.temporal.payload.context.NexusSerializationContext;
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
    private final @Nullable PayloadCodec headerCodec;
    private final List<ContextPropagator> contextPropagators;
    private final WorkerInterceptor[] interceptors;
    private final ActivityExecutionContextFactory executionContextFactory;

    public BaseActivityTaskExecutor(
        DataConverter dataConverter,
        List<ContextPropagator> contextPropagators,
        WorkerInterceptor[] interceptors,
        ActivityExecutionContextFactory executionContextFactory) {
      this.dataConverter = dataConverter;
      this.headerCodec = HeaderUtils.resolveHeaderCodec(dataConverter);
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
      // The result and failure of a standalone activity started from a Nexus operation handler are
      // returned to the Nexus caller, so they use the handler's Nexus context. Heartbeat details
      // use it too, because the server puts them into the timeout failure.
      NexusSerializationContext nexusContext = info.getNexusSerializationContext();
      DataConverter resultDataConverter =
          nexusContext != null
              ? dataConverter.withContext(nexusContext)
              : dataConverterWithActivityContext;

      try {
        Header header =
            new Header(
                info.getHeader().orElse(io.temporal.api.common.v1.Header.getDefaultInstance()),
                HeaderUtils.withContext(headerCodec, serializationContext));
        if (info.getHeader().isPresent()) {
          deserializeAndPopulateContext(header, contextPropagators);
        }

        ActivityInboundCallsInterceptor inboundCallsInterceptor = createRootInboundInterceptor();
        for (WorkerInterceptor interceptor : interceptors) {
          inboundCallsInterceptor = interceptor.interceptActivity(inboundCallsInterceptor);
        }
        inboundCallsInterceptor.init(context);

        Object[] args = provideArgs(info.getInput(), dataConverterWithActivityContext);
        ActivityOutput result =
            inboundCallsInterceptor.execute(
                new ActivityInboundCallsInterceptor.ActivityInput(header, args));
        if (context.isDoNotCompleteOnReturn()) {
          return new ActivityTaskHandler.Result(
              info.getActivityId(), null, null, null, context.isUseLocalManualCompletion());
        }

        return this.constructSuccessfulResultValue(info, result, resultDataConverter);
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
            resultDataConverter);
      } finally {
        if (context.isDoNotCompleteOnReturn()) {
          if (!context.isUseLocalManualCompletion()) {
            context.asyncCompletionStarted();
          }
          executionContextFactory.cleanupContext(info.getTaskToken(), false);
        } else {
          // if the activity is completed, we need to cancel the heartbeat
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
        ActivityInfoInternal info, ActivityOutput result, DataConverter resultDataConverter);

    ActivityTaskHandler.Result constructResultValue(
        ActivityInfoInternal info,
        @Nullable ActivityOutput result,
        DataConverter resultDataConverter) {
      RespondActivityTaskCompletedRequest.Builder request =
          RespondActivityTaskCompletedRequest.newBuilder();
      if (result != null) {
        Optional<Payloads> serialized = resultDataConverter.toPayloads(result.getResult());
        serialized.ifPresent(request::setResult);
      }
      return new ActivityTaskHandler.Result(
          info.getActivityId(), request.build(), null, null, false);
    }

    static void deserializeAndPopulateContext(
        @Nonnull Header header, @Nullable List<ContextPropagator> contextPropagatorList) {
      if (contextPropagatorList == null || contextPropagatorList.isEmpty()) {
        return;
      }

      for (ContextPropagator propagator : contextPropagatorList) {
        Map<String, Payload> headerData =
            propagator.encodeHeaders()
                ? header.getCodecDecodedValues()
                : new HashMap<>(header.getValues());
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
        ActivityInfoInternal info, ActivityOutput result, DataConverter resultDataConverter) {
      return constructResultValue(
          info,
          // if the expected result of the method is null, we don't publish result at all
          method.getReturnType() != Void.TYPE ? result : null,
          resultDataConverter);
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
        ActivityInfoInternal info, ActivityOutput result, DataConverter resultDataConverter) {
      return constructResultValue(info, result, resultDataConverter);
    }
  }
}
