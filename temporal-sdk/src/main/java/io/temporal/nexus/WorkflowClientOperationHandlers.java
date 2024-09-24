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

package io.temporal.nexus;

import io.nexusrpc.handler.*;
import io.nexusrpc.handler.OperationHandler;
import io.temporal.client.WorkflowClient;
import io.temporal.common.Experimental;
import io.temporal.internal.nexus.CurrentNexusOperationContext;
import io.temporal.internal.nexus.NexusOperationContextImpl;

/** WorkflowClientOperationHandlers can be used to create Temporal specific OperationHandlers */
@Experimental
public final class WorkflowClientOperationHandlers {
  /**
   * Helper to create {@link io.nexusrpc.handler.OperationHandler} instances that take a {@link
   * io.temporal.client.WorkflowClient}.
   */
  public static <T, R> OperationHandler<T, R> sync(
      SynchronousWorkflowClientOperationFunction<T, R> func) {
    return io.nexusrpc.handler.OperationHandler.sync(
        (OperationContext ctx, OperationStartDetails details, T input) -> {
          NexusOperationContextImpl nexusCtx = CurrentNexusOperationContext.get();
          return func.apply(ctx, details, nexusCtx.getWorkflowClient(), input);
        });
  }

  /**
   * Maps a workflow method to an {@link io.nexusrpc.handler.OperationHandler}.
   *
   * @param startMethod returns the workflow method reference to call
   * @return Operation handler to be used as an {@link OperationImpl}
   */
  public static <T, R> OperationHandler<T, R> fromWorkflowMethod(
      WorkflowMethodFactory<T, R> startMethod) {
    return new RunWorkflowOperation<>(
        (OperationContext context, OperationStartDetails details, WorkflowClient client, T input) ->
            WorkflowHandle.fromWorkflowMethod(
                startMethod.apply(context, details, client, input), input));
  }

  /**
   * Maps a workflow handle to an {@link io.nexusrpc.handler.OperationHandler}.
   *
   * @param handleFactory returns the workflow handle that will be mapped to the call
   * @return Operation handler to be used as an {@link OperationImpl}
   */
  public static <T, R> OperationHandler<T, R> fromWorkflowHandle(
      WorkflowHandleFactory<T, R> handleFactory) {
    return new RunWorkflowOperation<>(handleFactory);
  }

  /** Prohibits instantiation. */
  private WorkflowClientOperationHandlers() {}
}
