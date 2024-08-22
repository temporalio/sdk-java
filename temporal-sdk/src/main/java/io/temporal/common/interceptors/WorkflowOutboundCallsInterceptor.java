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

package io.temporal.common.interceptors;

import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.common.Experimental;
import io.temporal.common.SearchAttributeUpdate;
import io.temporal.workflow.*;
import io.temporal.workflow.Functions.Func;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Can be used to intercept calls from to workflow code into the Temporal APIs.
 *
 * <p>The calls to the interceptor are executed in the context of a workflow and must follow the
 * same rules all the other workflow code follows.
 *
 * <p>Prefer extending {@link WorkflowOutboundCallsInterceptorBase} and overriding only the methods
 * you need instead of implementing this interface directly. {@link
 * WorkflowOutboundCallsInterceptorBase} provides correct default implementations to all the methods
 * of this interface.
 *
 * <p>An instance may be created in {@link
 * WorkflowInboundCallsInterceptor#init(WorkflowOutboundCallsInterceptor)} and set by passing it
 * into {@code init} method of the {@code next} {@link WorkflowInboundCallsInterceptor} The
 * implementation must forward all the calls to the outbound interceptor passed as a {@code
 * outboundCalls} parameter to the {@code init} call.
 *
 * @see WorkerInterceptor#interceptWorkflow for the definition of "next" {@link
 *     WorkflowInboundCallsInterceptor}.
 */
@Experimental
public interface WorkflowOutboundCallsInterceptor {

  final class ActivityInput<R> {
    private final String activityName;
    private final Class<R> resultClass;
    private final Type resultType;
    private final Object[] args;
    private final ActivityOptions options;
    private final Header header;

    public ActivityInput(
        String activityName,
        Class<R> resultClass,
        Type resultType,
        Object[] args,
        ActivityOptions options,
        Header header) {
      this.activityName = activityName;
      this.resultClass = resultClass;
      this.resultType = resultType;
      this.args = args;
      this.options = options;
      this.header = header;
    }

    public String getActivityName() {
      return activityName;
    }

    public Class<R> getResultClass() {
      return resultClass;
    }

    public Type getResultType() {
      return resultType;
    }

    public Object[] getArgs() {
      return args;
    }

    public ActivityOptions getOptions() {
      return options;
    }

    public Header getHeader() {
      return header;
    }
  }

  final class ActivityOutput<R> {
    private final String activityId;
    private final Promise<R> result;

    public ActivityOutput(String activityId, Promise<R> result) {
      this.activityId = activityId;
      this.result = result;
    }

    public String getActivityId() {
      return activityId;
    }

    public Promise<R> getResult() {
      return result;
    }
  }

  final class LocalActivityInput<R> {
    private final String activityName;
    private final Class<R> resultClass;
    private final Type resultType;
    private final Object[] args;
    private final LocalActivityOptions options;
    private final Header header;

    public LocalActivityInput(
        String activityName,
        Class<R> resultClass,
        Type resultType,
        Object[] args,
        LocalActivityOptions options,
        Header header) {
      this.activityName = activityName;
      this.resultClass = resultClass;
      this.resultType = resultType;
      this.args = args;
      this.options = options;
      this.header = header;
    }

    public String getActivityName() {
      return activityName;
    }

    public Class<R> getResultClass() {
      return resultClass;
    }

    public Type getResultType() {
      return resultType;
    }

    public Object[] getArgs() {
      return args;
    }

    public LocalActivityOptions getOptions() {
      return options;
    }

    public Header getHeader() {
      return header;
    }
  }

  final class LocalActivityOutput<R> {
    private final Promise<R> result;

    public LocalActivityOutput(Promise<R> result) {
      this.result = result;
    }

    public Promise<R> getResult() {
      return result;
    }
  }

  final class ChildWorkflowInput<R> {
    private final String workflowId;
    private final String workflowType;
    private final Class<R> resultClass;
    private final Type resultType;
    private final Object[] args;
    private final ChildWorkflowOptions options;
    private final Header header;

    public ChildWorkflowInput(
        String workflowId,
        String workflowType,
        Class<R> resultClass,
        Type resultType,
        Object[] args,
        ChildWorkflowOptions options,
        Header header) {
      this.workflowId = workflowId;
      this.workflowType = workflowType;
      this.resultClass = resultClass;
      this.resultType = resultType;
      this.args = args;
      this.options = options;
      this.header = header;
    }

    public String getWorkflowId() {
      return workflowId;
    }

    public String getWorkflowType() {
      return workflowType;
    }

    public Class<R> getResultClass() {
      return resultClass;
    }

    public Type getResultType() {
      return resultType;
    }

    public Object[] getArgs() {
      return args;
    }

    public ChildWorkflowOptions getOptions() {
      return options;
    }

    public Header getHeader() {
      return header;
    }
  }

  final class ChildWorkflowOutput<R> {

    private final Promise<R> result;
    private final Promise<WorkflowExecution> workflowExecution;

    public ChildWorkflowOutput(Promise<R> result, Promise<WorkflowExecution> workflowExecution) {
      this.result = result;
      this.workflowExecution = workflowExecution;
    }

    public Promise<R> getResult() {
      return result;
    }

    public Promise<WorkflowExecution> getWorkflowExecution() {
      return workflowExecution;
    }
  }

  @Experimental
  final class ExecuteNexusOperationInput<R> {
    private final String endpoint;
    private final String service;
    private final String operation;
    private final Class<R> resultClass;
    private final Type resultType;
    private final Object arg;
    private final NexusOperationOptions options;
    private final Map<String, String> headers;

    public ExecuteNexusOperationInput(
        String endpoint,
        String service,
        String operation,
        Class<R> resultClass,
        Type resultType,
        Object arg,
        NexusOperationOptions options,
        Map<String, String> headers) {
      this.endpoint = endpoint;
      this.service = service;
      this.operation = operation;
      this.resultClass = resultClass;
      this.resultType = resultType;
      this.arg = arg;
      this.options = options;
      this.headers = headers;
    }

    public String getService() {
      return service;
    }

    public String getOperation() {
      return operation;
    }

    public Object getArg() {
      return arg;
    }

    public Class<R> getResultClass() {
      return resultClass;
    }

    public Type getResultType() {
      return resultType;
    }

    public String getEndpoint() {
      return endpoint;
    }

    public NexusOperationOptions getOptions() {
      return options;
    }

    public Map<String, String> getHeaders() {
      return headers;
    }
  }

  @Experimental
  final class ExecuteNexusOperationOutput<R> {
    private final Promise<R> result;
    private final Promise<Optional<String>> operationExecution;

    public ExecuteNexusOperationOutput(
        Promise<R> result, Promise<Optional<String>> operationExecution) {
      this.result = result;
      this.operationExecution = operationExecution;
    }

    public Promise<R> getResult() {
      return result;
    }

    public Promise<Optional<String>> getOperationExecution() {
      return operationExecution;
    }
  }

  final class SignalExternalInput {
    private final WorkflowExecution execution;
    private final String signalName;
    private final Header header;
    private final Object[] args;

    public SignalExternalInput(
        WorkflowExecution execution, String signalName, Header header, Object[] args) {
      this.execution = execution;
      this.signalName = signalName;
      this.header = header;
      this.args = args;
    }

    public WorkflowExecution getExecution() {
      return execution;
    }

    public String getSignalName() {
      return signalName;
    }

    public Header getHeader() {
      return header;
    }

    public Object[] getArgs() {
      return args;
    }
  }

  final class SignalExternalOutput {
    private final Promise<Void> result;

    public SignalExternalOutput(Promise<Void> result) {
      this.result = result;
    }

    public Promise<Void> getResult() {
      return result;
    }
  }

  final class CancelWorkflowInput {
    private final WorkflowExecution execution;

    public CancelWorkflowInput(WorkflowExecution execution) {
      this.execution = execution;
    }

    public WorkflowExecution getExecution() {
      return execution;
    }
  }

  final class CancelWorkflowOutput {
    private final Promise<Void> result;

    public CancelWorkflowOutput(Promise<Void> result) {
      this.result = result;
    }

    public Promise<Void> getResult() {
      return result;
    }
  }

  final class ContinueAsNewInput {
    private final @Nullable String workflowType;
    private final @Nullable ContinueAsNewOptions options;
    private final Object[] args;
    private final Header header;

    public ContinueAsNewInput(
        @Nullable String workflowType,
        @Nullable ContinueAsNewOptions options,
        Object[] args,
        Header header) {
      this.workflowType = workflowType;
      this.options = options;
      this.args = args;
      this.header = header;
    }

    /**
     * @return workflowType for the continue-as-new workflow run. null if continue-as-new should
     *     inherit the type of the original workflow run.
     */
    public @Nullable String getWorkflowType() {
      return workflowType;
    }

    /**
     * @return options for the continue-as-new workflow run. Can be null, in that case the values
     *     will be taken from the original workflow run.
     */
    public @Nullable ContinueAsNewOptions getOptions() {
      return options;
    }

    public Object[] getArgs() {
      return args;
    }

    public Header getHeader() {
      return header;
    }
  }

  final class SignalRegistrationRequest {
    private final String signalType;
    private final HandlerUnfinishedPolicy unfinishedPolicy;
    private final Class<?>[] argTypes;
    private final Type[] genericArgTypes;
    private final Functions.Proc1<Object[]> callback;

    // Kept for backward compatibility
    public SignalRegistrationRequest(
        String signalType,
        Class<?>[] argTypes,
        Type[] genericArgTypes,
        Functions.Proc1<Object[]> callback) {
      this.signalType = signalType;
      this.unfinishedPolicy = HandlerUnfinishedPolicy.WARN_AND_ABANDON;
      this.argTypes = argTypes;
      this.genericArgTypes = genericArgTypes;
      this.callback = callback;
    }

    public SignalRegistrationRequest(
        String signalType,
        HandlerUnfinishedPolicy unfinishedPolicy,
        Class<?>[] argTypes,
        Type[] genericArgTypes,
        Functions.Proc1<Object[]> callback) {
      this.signalType = signalType;
      this.unfinishedPolicy = unfinishedPolicy;
      this.argTypes = argTypes;
      this.genericArgTypes = genericArgTypes;
      this.callback = callback;
    }

    public String getSignalType() {
      return signalType;
    }

    public HandlerUnfinishedPolicy getUnfinishedPolicy() {
      return unfinishedPolicy;
    }

    public Class<?>[] getArgTypes() {
      return argTypes;
    }

    public Type[] getGenericArgTypes() {
      return genericArgTypes;
    }

    public Functions.Proc1<Object[]> getCallback() {
      return callback;
    }
  }

  final class RegisterSignalHandlersInput {
    private final List<SignalRegistrationRequest> requests;

    public RegisterSignalHandlersInput(List<SignalRegistrationRequest> requests) {
      this.requests = requests;
    }

    public List<SignalRegistrationRequest> getRequests() {
      return requests;
    }
  }

  @Experimental
  final class UpdateRegistrationRequest {
    private final String updateName;
    private final HandlerUnfinishedPolicy unfinishedPolicy;
    private final Class<?>[] argTypes;
    private final Type[] genericArgTypes;
    private final Functions.Func1<Object[], Object> executeCallback;
    private final Functions.Proc1<Object[]> validateCallback;

    public UpdateRegistrationRequest(
        String updateName,
        HandlerUnfinishedPolicy unfinishedPolicy,
        Class<?>[] argTypes,
        Type[] genericArgTypes,
        Functions.Proc1<Object[]> validateCallback,
        Functions.Func1<Object[], Object> executeCallback) {
      this.updateName = updateName;
      this.unfinishedPolicy = unfinishedPolicy;
      this.argTypes = argTypes;
      this.genericArgTypes = genericArgTypes;
      this.validateCallback = validateCallback;
      this.executeCallback = executeCallback;
    }

    public String getUpdateName() {
      return updateName;
    }

    public HandlerUnfinishedPolicy getUnfinishedPolicy() {
      return unfinishedPolicy;
    }

    public Class<?>[] getArgTypes() {
      return argTypes;
    }

    public Type[] getGenericArgTypes() {
      return genericArgTypes;
    }

    public Functions.Proc1<Object[]> getValidateCallback() {
      return validateCallback;
    }

    public Functions.Func1<Object[], Object> getExecuteCallback() {
      return executeCallback;
    }
  }

  @Experimental
  final class RegisterUpdateHandlersInput {
    private final List<UpdateRegistrationRequest> requests;

    public RegisterUpdateHandlersInput(List<UpdateRegistrationRequest> requests) {
      this.requests = requests;
    }

    public List<UpdateRegistrationRequest> getRequests() {
      return requests;
    }
  }

  final class RegisterQueryInput {
    private final String queryType;
    private final Class<?>[] argTypes;
    private final Type[] genericArgTypes;
    private final Functions.Func1<Object[], Object> callback;

    public RegisterQueryInput(
        String queryType,
        Class<?>[] argTypes,
        Type[] genericArgTypes,
        Functions.Func1<Object[], Object> callback) {
      this.queryType = queryType;
      this.argTypes = argTypes;
      this.genericArgTypes = genericArgTypes;
      this.callback = callback;
    }

    public String getQueryType() {
      return queryType;
    }

    public Class<?>[] getArgTypes() {
      return argTypes;
    }

    public Type[] getGenericArgTypes() {
      return genericArgTypes;
    }

    public Functions.Func1<Object[], Object> getCallback() {
      return callback;
    }
  }

  final class RegisterDynamicQueryHandlerInput {
    private final DynamicQueryHandler handler;

    public RegisterDynamicQueryHandlerInput(DynamicQueryHandler handler) {
      this.handler = handler;
    }

    public DynamicQueryHandler getHandler() {
      return handler;
    }
  }

  final class RegisterDynamicSignalHandlerInput {
    private final DynamicSignalHandler handler;

    public RegisterDynamicSignalHandlerInput(DynamicSignalHandler handler) {
      this.handler = handler;
    }

    public DynamicSignalHandler getHandler() {
      return handler;
    }
  }

  @Experimental
  final class RegisterDynamicUpdateHandlerInput {
    private final DynamicUpdateHandler handler;

    public RegisterDynamicUpdateHandlerInput(DynamicUpdateHandler handler) {
      this.handler = handler;
    }

    public DynamicUpdateHandler getHandler() {
      return handler;
    }
  }

  <R> ActivityOutput<R> executeActivity(ActivityInput<R> input);

  <R> LocalActivityOutput<R> executeLocalActivity(LocalActivityInput<R> input);

  <R> ChildWorkflowOutput<R> executeChildWorkflow(ChildWorkflowInput<R> input);

  @Experimental
  <R> ExecuteNexusOperationOutput<R> executeNexusOperation(ExecuteNexusOperationInput<R> input);

  Random newRandom();

  SignalExternalOutput signalExternalWorkflow(SignalExternalInput input);

  CancelWorkflowOutput cancelWorkflow(CancelWorkflowInput input);

  void sleep(Duration duration);

  boolean await(Duration timeout, String reason, Supplier<Boolean> unblockCondition);

  void await(String reason, Supplier<Boolean> unblockCondition);

  Promise<Void> newTimer(Duration duration);

  <R> R sideEffect(Class<R> resultClass, Type resultType, Func<R> func);

  <R> R mutableSideEffect(
      String id, Class<R> resultClass, Type resultType, BiPredicate<R, R> updated, Func<R> func);

  int getVersion(String changeId, int minSupported, int maxSupported);

  void continueAsNew(ContinueAsNewInput input);

  void registerQuery(RegisterQueryInput input);

  void registerSignalHandlers(RegisterSignalHandlersInput input);

  @Experimental
  void registerUpdateHandlers(RegisterUpdateHandlersInput input);

  void registerDynamicSignalHandler(RegisterDynamicSignalHandlerInput handler);

  void registerDynamicQueryHandler(RegisterDynamicQueryHandlerInput input);

  @Experimental
  void registerDynamicUpdateHandler(RegisterDynamicUpdateHandlerInput input);

  UUID randomUUID();

  void upsertSearchAttributes(Map<String, ?> searchAttributes);

  void upsertTypedSearchAttributes(SearchAttributeUpdate<?>... searchAttributeUpdates);

  void upsertMemo(Map<String, Object> memo);

  /**
   * Intercepts creation of a workflow child thread.
   *
   * <p>Please note, that "workflow child thread" and "child workflow" are different and independent
   * concepts.
   *
   * @param runnable thread function to run
   * @param detached if this thread is detached from the parent {@link CancellationScope}
   * @param name name of the thread
   * @return created WorkflowThread
   */
  Object newChildThread(Runnable runnable, boolean detached, String name);

  long currentTimeMillis();
}
