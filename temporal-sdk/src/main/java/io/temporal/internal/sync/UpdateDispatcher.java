package io.temporal.internal.sync;

import static io.temporal.internal.common.InternalUtils.TEMPORAL_RESERVED_PREFIX;

import io.temporal.api.common.v1.Payloads;
import io.temporal.api.sdk.v1.WorkflowInteractionDefinition;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.EncodedValues;
import io.temporal.common.interceptors.Header;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor.UpdateInput;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor.UpdateOutput;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor.UpdateRegistrationRequest;
import io.temporal.workflow.DynamicUpdateHandler;
import io.temporal.workflow.HandlerUnfinishedPolicy;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class UpdateDispatcher {
  private static final Logger log = LoggerFactory.getLogger(UpdateDispatcher.class);

  private final DataConverter dataConverterWithWorkflowContext;
  private final Map<String, WorkflowOutboundCallsInterceptor.UpdateRegistrationRequest>
      updateCallbacks = new HashMap<>();

  private DynamicUpdateHandler dynamicUpdateHandler;
  private WorkflowInboundCallsInterceptor inboundCallsInterceptor;
  private Map<String, UpdateHandlerInfo> runningUpdateHandlers = new TreeMap<>();

  public UpdateDispatcher(DataConverter dataConverterWithWorkflowContext) {
    this.dataConverterWithWorkflowContext = dataConverterWithWorkflowContext;
  }

  public void setInboundCallsInterceptor(WorkflowInboundCallsInterceptor inboundCallsInterceptor) {
    this.inboundCallsInterceptor = inboundCallsInterceptor;
  }

  public void handleValidateUpdate(
      String updateName, String updateId, Optional<Payloads> input, long eventId, Header header) {
    WorkflowOutboundCallsInterceptor.UpdateRegistrationRequest handler =
        updateCallbacks.get(updateName);
    Object[] args;
    HandlerUnfinishedPolicy policy;
    if (updateName.startsWith(TEMPORAL_RESERVED_PREFIX)) {
      throw new IllegalArgumentException(
          "Unknown update name: " + updateName + ", knownTypes=" + updateCallbacks.keySet());
    }
    if (handler == null) {
      if (dynamicUpdateHandler == null) {
        throw new IllegalArgumentException(
            "Unknown update name: " + updateName + ", knownTypes=" + updateCallbacks.keySet());
      }
      args = new Object[] {new EncodedValues(input, dataConverterWithWorkflowContext)};
      policy = dynamicUpdateHandler.getUnfinishedPolicy(updateName);
    } else {
      args =
          dataConverterWithWorkflowContext.fromPayloads(
              input, handler.getArgTypes(), handler.getGenericArgTypes());
      policy = handler.getUnfinishedPolicy();
    }
    runningUpdateHandlers.put(updateId, new UpdateHandlerInfo(updateId, updateName, policy));
    try {
      inboundCallsInterceptor.validateUpdate(
          new WorkflowInboundCallsInterceptor.UpdateInput(updateName, header, args));
    } finally {
      runningUpdateHandlers.remove(updateId);
    }
  }

  public Optional<Payloads> handleExecuteUpdate(
      String updateName, String updateId, Optional<Payloads> input, long eventId, Header header) {
    WorkflowOutboundCallsInterceptor.UpdateRegistrationRequest handler =
        updateCallbacks.get(updateName);
    Object[] args;
    HandlerUnfinishedPolicy policy;
    if (handler == null) {
      if (dynamicUpdateHandler == null) {
        throw new IllegalArgumentException(
            "Unknown update name: " + updateName + ", knownTypes=" + updateCallbacks.keySet());
      }
      args = new Object[] {new EncodedValues(input, dataConverterWithWorkflowContext)};
      policy = dynamicUpdateHandler.getUnfinishedPolicy(updateName);
    } else {
      args =
          dataConverterWithWorkflowContext.fromPayloads(
              input, handler.getArgTypes(), handler.getGenericArgTypes());
      policy = handler.getUnfinishedPolicy();
    }

    runningUpdateHandlers.put(updateId, new UpdateHandlerInfo(updateId, updateName, policy));
    boolean threadDestroyed = false;
    try {
      Object result =
          inboundCallsInterceptor
              .executeUpdate(
                  new WorkflowInboundCallsInterceptor.UpdateInput(updateName, header, args))
              .getResult();
      return dataConverterWithWorkflowContext.toPayloads(result);
    } catch (DestroyWorkflowThreadError e) {
      threadDestroyed = true;
      throw e;
    } finally {
      // If the thread was destroyed the user did not finish the handler
      if (!threadDestroyed) {
        runningUpdateHandlers.remove(updateId);
      }
    }
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

  public Map<String, UpdateHandlerInfo> getRunningUpdateHandlers() {
    return runningUpdateHandlers;
  }

  public List<WorkflowInteractionDefinition> getUpdateHandlers() {
    List<WorkflowInteractionDefinition> handlers = new ArrayList<>();
    for (Map.Entry<String, WorkflowOutboundCallsInterceptor.UpdateRegistrationRequest> entry :
        updateCallbacks.entrySet()) {
      UpdateRegistrationRequest handler = entry.getValue();
      handlers.add(
          WorkflowInteractionDefinition.newBuilder()
              .setName(handler.getUpdateName())
              .setDescription(handler.getDescription())
              .build());
    }
    if (dynamicUpdateHandler != null) {
      handlers.add(
          WorkflowInteractionDefinition.newBuilder()
              .setDescription(dynamicUpdateHandler.getDescription())
              .build());
    }
    handlers.sort(Comparator.comparing(WorkflowInteractionDefinition::getName));
    return handlers;
  }
}
