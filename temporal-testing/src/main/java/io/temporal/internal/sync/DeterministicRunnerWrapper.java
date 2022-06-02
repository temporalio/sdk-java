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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.*;

public class DeterministicRunnerWrapper implements InvocationHandler {
  private final InvocationHandler invocationHandler;
  private final WorkflowThreadExecutor workflowThreadExecutor;

  public DeterministicRunnerWrapper(
      InvocationHandler invocationHandler, WorkflowThreadExecutor workflowThreadExecutor) {
    this.invocationHandler = Objects.requireNonNull(invocationHandler);
    this.workflowThreadExecutor = Objects.requireNonNull(workflowThreadExecutor);
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    CompletableFuture<Object> result = new CompletableFuture<>();
    DeterministicRunner runner =
        new DeterministicRunnerImpl(
            workflowThreadExecutor,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              try {
                result.complete(invocationHandler.invoke(proxy, method, args));
              } catch (Throwable throwable) {
                result.completeExceptionally(throwable);
              }
            });
    // Used to execute activities under TestActivityEnvironment
    // So it is expected that a workflow thread is blocked for a long time.
    runner.runUntilAllBlocked(Long.MAX_VALUE);
    try {
      return result.get();
    } catch (ExecutionException e) {
      throw e.getCause();
    }
  }
}
