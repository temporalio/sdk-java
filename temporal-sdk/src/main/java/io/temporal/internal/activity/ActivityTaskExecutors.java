/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.internal.activity;

import static io.temporal.internal.activity.ActivityTaskHandlerImpl.mapToActivityFailure;

import com.uber.m3.tally.Scope;
import io.temporal.activity.ActivityExecutionContext;
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
import io.temporal.internal.worker.ActivityTaskHandler;
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
    protected final DataConverter dataConverter;
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
      ActivityExecutionContext context = executionContextFactory.createContext(info, metricsScope);
      Optional<Payloads> input = info.getInput();

      try {
        info.getHeader()
            .ifPresent(value -> deserializeAndPopulateContext(value, contextPropagators));

        ActivityInboundCallsInterceptor inboundCallsInterceptor = createRootInboundInterceptor();
        for (WorkerInterceptor interceptor : interceptors) {
          inboundCallsInterceptor = interceptor.interceptActivity(inboundCallsInterceptor);
        }
        inboundCallsInterceptor.init(context);

        Object[] args = provideArgs(input);
        Header header =
            new Header(
                info.getHeader().orElse(io.temporal.api.common.v1.Header.getDefaultInstance()));
        ActivityOutput result =
            inboundCallsInterceptor.execute(
                new ActivityInboundCallsInterceptor.ActivityInput(header, args));
        if (context.isDoNotCompleteOnReturn()) {
          return new ActivityTaskHandler.Result(
              info.getActivityId(), null, null, null, null, context.isUseLocalManualCompletion());
        }

        return this.constructSuccessfulResultValue(info, result);
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
        } else {
          log.warn(
              "{} failure. ActivityId={}, activityType={}, attempt={}",
              local ? "Local activity" : "Activity",
              info.getActivityId(),
              info.getActivityType(),
              info.getAttempt(),
              ex);
        }

        return mapToActivityFailure(ex, info.getActivityId(), metricsScope, local, dataConverter);
      }
    }

    abstract ActivityInboundCallsInterceptor createRootInboundInterceptor();

    abstract Object[] provideArgs(Optional<Payloads> input);

    protected abstract ActivityTaskHandler.Result constructSuccessfulResultValue(
        ActivityInfoInternal info, ActivityOutput result);

    ActivityTaskHandler.Result constructResultValue(
        ActivityInfoInternal info, @Nullable ActivityOutput result) {
      RespondActivityTaskCompletedRequest.Builder request =
          RespondActivityTaskCompletedRequest.newBuilder();
      if (result != null) {
        Optional<Payloads> serialized = dataConverter.toPayloads(result.getResult());
        serialized.ifPresent(request::setResult);
      }
      return new ActivityTaskHandler.Result(
          info.getActivityId(), request.build(), null, null, null, false);
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
    Object[] provideArgs(Optional<Payloads> input) {
      return DataConverter.arrayFromPayloads(
          dataConverter, input, method.getParameterTypes(), method.getGenericParameterTypes());
    }

    @Override
    protected ActivityTaskHandler.Result constructSuccessfulResultValue(
        ActivityInfoInternal info, ActivityOutput result) {
      return constructResultValue(
          info,
          // if the expected result of the method is null, we don't publish result at all
          method.getReturnType() != Void.TYPE ? result : null);
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
    Object[] provideArgs(Optional<Payloads> input) {
      EncodedValues encodedValues = new EncodedValues(input, dataConverter);
      return new Object[] {encodedValues};
    }

    @Override
    protected ActivityTaskHandler.Result constructSuccessfulResultValue(
        ActivityInfoInternal info, ActivityOutput result) {
      return constructResultValue(info, result);
    }
  }
}
