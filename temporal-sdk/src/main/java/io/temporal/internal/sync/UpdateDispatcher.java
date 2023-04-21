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
import io.temporal.common.converter.EncodedValues;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor.UpdateInput;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor.UpdateOutput;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor.UpdateRegistrationRequest;
import io.temporal.workflow.DynamicUpdateHandler;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class UpdateDispatcher {
  private static final Logger log = LoggerFactory.getLogger(UpdateDispatcher.class);

  private final DataConverter dataConverterWithWorkflowContext;
  private final Map<String, WorkflowOutboundCallsInterceptor.UpdateRegistrationRequest>
      updateCallbacks = new HashMap<>();

  private DynamicUpdateHandler dynamicUpdateHandler;
  private WorkflowInboundCallsInterceptor inboundCallsInterceptor;

  public UpdateDispatcher(DataConverter dataConverterWithWorkflowContext) {
    this.dataConverterWithWorkflowContext = dataConverterWithWorkflowContext;
  }

  public void setInboundCallsInterceptor(WorkflowInboundCallsInterceptor inboundCallsInterceptor) {
    this.inboundCallsInterceptor = inboundCallsInterceptor;
  }

  public void handleValidateUpdate(String updateName, Optional<Payloads> input, long eventId) {
    WorkflowOutboundCallsInterceptor.UpdateRegistrationRequest handler =
        updateCallbacks.get(updateName);
    Object[] args;
    if (handler == null) {
      if (dynamicUpdateHandler == null) {
        throw new IllegalArgumentException(
            "Unknown update name: " + updateName + ", knownTypes=" + updateCallbacks.keySet());
      }
      args = new Object[] {new EncodedValues(input, dataConverterWithWorkflowContext)};
    } else {
      args =
          DataConverter.arrayFromPayloads(
              dataConverterWithWorkflowContext,
              input,
              handler.getArgTypes(),
              handler.getGenericArgTypes());
    }

    inboundCallsInterceptor.validateUpdate(
        new WorkflowInboundCallsInterceptor.UpdateInput(updateName, args));
  }

  public Optional<Payloads> handleExecuteUpdate(
      String updateName, Optional<Payloads> input, long eventId) {
    WorkflowOutboundCallsInterceptor.UpdateRegistrationRequest handler =
        updateCallbacks.get(updateName);
    Object[] args;
    if (handler == null) {
      if (dynamicUpdateHandler == null) {
        throw new IllegalArgumentException(
            "Unknown update name: " + updateName + ", knownTypes=" + updateCallbacks.keySet());
      }
      args = new Object[] {new EncodedValues(input, dataConverterWithWorkflowContext)};
    } else {
      args =
          DataConverter.arrayFromPayloads(
              dataConverterWithWorkflowContext,
              input,
              handler.getArgTypes(),
              handler.getGenericArgTypes());
    }
    Object result =
        inboundCallsInterceptor
            .executeUpdate(new WorkflowInboundCallsInterceptor.UpdateInput(updateName, args))
            .getResult();
    return dataConverterWithWorkflowContext.toPayloads(result);
  }

  public void registerUpdateHandlers(
      WorkflowOutboundCallsInterceptor.RegisterUpdateHandlersInput input) {
    for (WorkflowOutboundCallsInterceptor.UpdateRegistrationRequest request : input.getRequests()) {
      String updateName = request.getUpdateName();
      if (updateCallbacks.containsKey(updateName)) {
        throw new IllegalStateException("Update \"" + updateName + "\" is already registered");
      }
      updateCallbacks.put(updateName, request);
    }
  }

  public void registerDynamicUpdateHandler(
      WorkflowOutboundCallsInterceptor.RegisterDynamicUpdateHandlerInput input) {
    dynamicUpdateHandler = input.getHandler();
  }

  public void handleInterceptedValidateUpdate(UpdateInput input) {
    String updateName = input.getUpdateName();
    Object[] args = input.getArguments();
    UpdateRegistrationRequest handler = updateCallbacks.get(updateName);
    if (handler == null) {
      if (dynamicUpdateHandler != null) {
        dynamicUpdateHandler.handleValidate(updateName, (EncodedValues) args[0]);
      } else {
        throw new IllegalStateException("Unknown update name: " + updateName);
      }
    } else {
      handler.getValidateCallback().apply(args);
    }
  }

  public UpdateOutput handleInterceptedExecuteUpdate(UpdateInput input) {
    String updateName = input.getUpdateName();
    Object[] args = input.getArguments();
    UpdateRegistrationRequest handler = updateCallbacks.get(updateName);
    Object result;
    if (handler == null) {
      if (dynamicUpdateHandler != null) {
        result = dynamicUpdateHandler.handleExecute(updateName, (EncodedValues) args[0]);
      } else {
        throw new IllegalStateException("Unknown update name: " + updateName);
      }
    } else {
      result = handler.getExecuteCallback().apply(args);
    }
    return new WorkflowInboundCallsInterceptor.UpdateOutput(result);
  }
}
