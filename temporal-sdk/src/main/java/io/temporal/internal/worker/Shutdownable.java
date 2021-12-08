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

package io.temporal.internal.worker;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public interface Shutdownable {

  boolean isShutdown();

  boolean isTerminated();

  /**
   * @param shutdownManager provides toolset to get a Future for a shutdown of instances that have
   *     both non-blocking and not returning a Future on a completion shutdown methods (like {@link
   *     ExecutorService#shutdown()})
   * @param interruptTasks if the threads processing user code (like workflows, workflow tasks or
   *     activities) should be interrupted, or we want to wait for their full graceful completion
   * @return CompletableFuture which should be completed when awaiting downstream dependencies can
   *     proceed with their own shutdown. Should never be completed exceptionally {@link
   *     CompletableFuture#exceptionally(Function)} as downstream dependencies have no use of this
   *     information (they need to perform a shutdown anyway), and it complicates the shutdown flow.
   */
  CompletableFuture<Void> shutdown(ShutdownManager shutdownManager, boolean interruptTasks);

  void awaitTermination(long timeout, TimeUnit unit);
}
