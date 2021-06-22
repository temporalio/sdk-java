/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.internal.sync;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class DeterministicRunnerWrapper implements InvocationHandler {

  private final InvocationHandler invocationHandler;

  public DeterministicRunnerWrapper(InvocationHandler invocationHandler) {
    this.invocationHandler = Objects.requireNonNull(invocationHandler);
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    CompletableFuture<Object> result = new CompletableFuture<>();
    DeterministicRunner runner =
        new DeterministicRunnerImpl(
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
