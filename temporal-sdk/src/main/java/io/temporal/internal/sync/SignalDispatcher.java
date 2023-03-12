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

package io.temporal.internal.sync;

import io.temporal.api.common.v1.Payloads;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DataConverterException;
import io.temporal.common.converter.EncodedValues;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.worker.MetricsType;
import io.temporal.workflow.DynamicSignalHandler;
import io.temporal.workflow.Workflow;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class SignalDispatcher {
  private static final Logger log = LoggerFactory.getLogger(SignalDispatcher.class);

  private final DataConverter dataConverterWithWorkflowContext;
  private final Map<String, WorkflowOutboundCallsInterceptor.SignalRegistrationRequest>
      signalCallbacks = new HashMap<>();

  private WorkflowInboundCallsInterceptor inboundCallsInterceptor;
  private DynamicSignalHandler dynamicSignalHandler;

  /** Buffers signals which don't have a registered listener. */
  private final Queue<SignalData> signalBuffer = new ArrayDeque<>();

  public SignalDispatcher(DataConverter dataConverterWithWorkflowContext) {
    this.dataConverterWithWorkflowContext = dataConverterWithWorkflowContext;
  }

  public void setInboundCallsInterceptor(WorkflowInboundCallsInterceptor inboundCallsInterceptor) {
    this.inboundCallsInterceptor = inboundCallsInterceptor;
  }

  /** Called from the interceptor tail */
  public void handleInterceptedSignal(WorkflowInboundCallsInterceptor.SignalInput input) {
    String signalName = input.getSignalName();
    Object[] args = input.getArguments();
    WorkflowOutboundCallsInterceptor.SignalRegistrationRequest handler =
        signalCallbacks.get(signalName);
    if (handler == null) {
      if (dynamicSignalHandler != null) {
        dynamicSignalHandler.handle(signalName, (EncodedValues) args[0]);
        return;
      }
      throw new IllegalStateException("Unknown signal type: " + signalName);
    } else {
      handler.getCallback().apply(args);
    }
  }

  public void handleSignal(String signalName, Optional<Payloads> input, long eventId) {
    WorkflowOutboundCallsInterceptor.SignalRegistrationRequest handler =
        signalCallbacks.get(signalName);
    Object[] args;
    if (handler == null) {
      if (dynamicSignalHandler == null) {
        signalBuffer.add(new SignalData(signalName, input, eventId));
        return;
      }
      args = new Object[] {new EncodedValues(input, dataConverterWithWorkflowContext)};
    } else {
      try {
        args =
            DataConverter.arrayFromPayloads(
                dataConverterWithWorkflowContext,
                input,
                handler.getArgTypes(),
                handler.getGenericArgTypes());
      } catch (DataConverterException e) {
        logSerializationException(signalName, eventId, e);
        return;
      }
    }
    inboundCallsInterceptor.handleSignal(
        new WorkflowInboundCallsInterceptor.SignalInput(signalName, args, eventId));
  }

  public void registerSignalHandlers(
      WorkflowOutboundCallsInterceptor.RegisterSignalHandlersInput input) {
    for (WorkflowOutboundCallsInterceptor.SignalRegistrationRequest request : input.getRequests()) {
      String signalType = request.getSignalType();
      if (signalCallbacks.containsKey(signalType)) {
        throw new IllegalStateException("Signal \"" + signalType + "\" is already registered");
      }
      signalCallbacks.put(signalType, request);
    }
    for (SignalData signalData : signalBuffer) {
      handleSignal(signalData.getSignalName(), signalData.getPayload(), signalData.getEventId());
    }
  }

  public void registerDynamicSignalHandler(
      WorkflowOutboundCallsInterceptor.RegisterDynamicSignalHandlerInput input) {
    dynamicSignalHandler = input.getHandler();
    for (SignalData signalData : signalBuffer) {
      dynamicSignalHandler.handle(
          signalData.getSignalName(),
          new EncodedValues(signalData.getPayload(), dataConverterWithWorkflowContext));
    }
  }

  private void logSerializationException(
      String signalName, Long eventId, DataConverterException exception) {
    log.error(
        "Failure deserializing signal input for \""
            + signalName
            + "\" at eventId "
            + eventId
            + ". Dropping it.",
        exception);
    Workflow.getMetricsScope().counter(MetricsType.CORRUPTED_SIGNALS_COUNTER).inc(1);
  }

  private static class SignalData {
    private final String signalName;
    private final Optional<Payloads> payload;
    private final long eventId;

    private SignalData(String signalName, Optional<Payloads> payload, long eventId) {
      this.signalName = Objects.requireNonNull(signalName);
      this.payload = Objects.requireNonNull(payload);
      this.eventId = eventId;
    }

    public String getSignalName() {
      return signalName;
    }

    public Optional<Payloads> getPayload() {
      return payload;
    }

    public long getEventId() {
      return eventId;
    }
  }
}
