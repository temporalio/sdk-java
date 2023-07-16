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

import io.temporal.common.Experimental;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Intercepts inbound calls to the workflow execution on the worker side.
 *
 * <p>An instance should be created in {@link
 * WorkerInterceptor#interceptWorkflow(WorkflowInboundCallsInterceptor)}.
 *
 * <p>The calls to this interceptor are executed under workflow context, all the rules and
 * restrictions on the workflow code apply. See {@link io.temporal.workflow}.
 *
 * <p>Prefer extending {@link WorkflowInboundCallsInterceptorBase} and overriding only the methods
 * you need instead of implementing this interface directly. {@link
 * WorkflowInboundCallsInterceptorBase} provides correct default implementations to all the methods
 * of this interface.
 *
 * <p>The implementation must forward all the calls to {@code next}, but it may change the input
 * parameters.
 *
 * @see WorkerInterceptor#interceptWorkflow(WorkflowInboundCallsInterceptor) for a definition of
 *     "next" {@link WorkflowInboundCallsInterceptor}
 */
@Experimental
public interface WorkflowInboundCallsInterceptor {

  final class WorkflowInput {
    private final Header header;
    private final Object[] arguments;

    public WorkflowInput(Header header, Object[] arguments) {
      this.header = header;
      this.arguments = arguments;
    }

    public Header getHeader() {
      return header;
    }

    public Object[] getArguments() {
      return arguments;
    }
  }

  final class WorkflowOutput {
    private final Object result;

    public WorkflowOutput(Object result) {
      this.result = result;
    }

    public Object getResult() {
      return result;
    }
  }

  final class SignalInput {
    private final String signalName;
    private final Object[] arguments;
    private final long EventId;
    private final Header header;

    public SignalInput(String signalName, Object[] arguments, long eventId, Header header) {
      this.signalName = signalName;
      this.arguments = arguments;
      EventId = eventId;
      this.header = header;
    }

    public String getSignalName() {
      return signalName;
    }

    public Object[] getArguments() {
      return arguments;
    }

    public long getEventId() {
      return EventId;
    }

    public Header getHeader() {
      return header;
    }
  }

  final class QueryInput {
    private final String queryName;
    private final Header header;
    private final Object[] arguments;

    public QueryInput(String queryName, Header header, Object[] arguments) {
      this.queryName = queryName;
      this.header = header;
      this.arguments = arguments;
    }

    public String getQueryName() {
      return queryName;
    }

    public Header getHeader() {
      return header;
    }

    public Object[] getArguments() {
      return arguments;
    }
  }

  final class QueryOutput {
    private final Object result;

    public QueryOutput(Object result) {
      this.result = result;
    }

    public Object getResult() {
      return result;
    }
  }

  @Experimental
  final class UpdateInput {
    private final String updateName;
    private final Header header;
    private final Object[] arguments;

    public UpdateInput(String updateName, Header header, Object[] arguments) {
      this.updateName = updateName;
      this.header = header;
      this.arguments = arguments;
    }

    public String getUpdateName() {
      return updateName;
    }

    public Header getHeader() {
      return header;
    }

    public Object[] getArguments() {
      return arguments;
    }
  }

  @Experimental
  final class UpdateOutput {
    private final Object result;

    public UpdateOutput(Object result) {
      this.result = result;
    }

    public Object getResult() {
      return result;
    }
  }

  /**
   * Called when workflow class is instantiated. May create a {@link
   * WorkflowOutboundCallsInterceptor} instance. The instance must forward all the calls to {@code
   * outboundCalls}, but it may change the input parameters.
   *
   * <p>The instance should be passed into the {next.init(newWorkflowOutboundCallsInterceptor)}.
   *
   * @param outboundCalls an existing interceptor instance to be proxied by the interceptor created
   *     inside this method
   * @see WorkerInterceptor#interceptWorkflow for the definition of "next" {@link
   *     WorkflowInboundCallsInterceptor}
   */
  void init(WorkflowOutboundCallsInterceptor outboundCalls);

  /**
   * Called when workflow main method is called.
   *
   * @return result of the workflow execution.
   */
  WorkflowOutput execute(WorkflowInput input);

  /** Called when signal is delivered to a workflow execution. */
  void handleSignal(SignalInput input);

  /** Called when a workflow is queried. */
  QueryOutput handleQuery(QueryInput input);

  /**
   * Called when update workflow execution request is delivered to a workflow execution, before the
   * update is executed.
   */
  @Experimental
  void validateUpdate(UpdateInput input);

  /**
   * Called when update workflow execution request is delivered to a workflow execution, after
   * passing the validator.
   */
  @Experimental
  UpdateOutput executeUpdate(UpdateInput input);

  /**
   * Intercepts creation of the workflow main method thread
   *
   * @param runnable thread function to run
   * @param name name of the thread, optional
   * @return created workflow thread. Should be treated as a pass-through object that shouldn't be
   *     manipulated in any way by the interceptor code.
   */
  @Nonnull
  Object newWorkflowMethodThread(Runnable runnable, @Nullable String name);

  /**
   * Intercepts creation of a workflow callback thread
   *
   * @param runnable thread function to run
   * @param name name of the thread, optional
   * @return created workflow thread. Should be treated as a pass-through object that shouldn't be
   *     manipulated in any way by the interceptor code.
   */
  @Nonnull
  Object newCallbackThread(Runnable runnable, @Nullable String name);
}
