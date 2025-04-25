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

import io.temporal.common.context.ContextPropagator;
import io.temporal.internal.worker.WorkflowExecutorCache;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RootWorkflowThreadImpl extends WorkflowThreadImpl {
  private static final Logger log = LoggerFactory.getLogger(RootWorkflowThreadImpl.class);

  RootWorkflowThreadImpl(
      WorkflowThreadExecutor workflowThreadExecutor,
      SyncWorkflowContext syncWorkflowContext,
      DeterministicRunnerImpl runner,
      @Nonnull String name,
      int priority,
      boolean detached,
      CancellationScopeImpl parentCancellationScope,
      Runnable runnable,
      WorkflowExecutorCache cache,
      List<ContextPropagator> contextPropagators,
      Map<String, Object> propagatedContexts) {
    super(
        workflowThreadExecutor,
        syncWorkflowContext,
        runner,
        name,
        priority,
        detached,
        parentCancellationScope,
        runnable,
        cache,
        contextPropagators,
        propagatedContexts);
  }

  @Override
  public void yield(String reason, Supplier<Boolean> unblockCondition)
      throws DestroyWorkflowThreadError {
    log.warn(
        "Detected root workflow thread yielding {}. This can happen by making blocking calls during workflow instance creating, such as executing an activity inside a @WorkflowInit constructor. This can cause issues, like Queries or Updates rejection because the workflow instance creation is delayed",
        getName());
    super.yield(reason, unblockCondition);
  }
}
