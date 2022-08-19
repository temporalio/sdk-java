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

package io.temporal.internal.replay;

import io.temporal.api.failure.v1.Failure;
import io.temporal.common.context.ContextPropagator;
import io.temporal.worker.WorkflowImplementationOptions;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Core top level workflow context */
public interface WorkflowContext {
  ReplayWorkflowContext getReplayContext();

  /**
   * Convert exception to the serialized Failure that can be reported to the server.<br>
   * This method is needed when framework code needs to serialize a {@link
   * io.temporal.failure.TemporalFailure} instance with details object produced by the application
   * code.<br>
   * The framework code is not aware of DataConverter so this is working around this layering.
   *
   * @param exception throwable to convert
   * @return Serialized failure
   */
  Failure mapExceptionToFailure(Throwable exception);

  @Nonnull
  WorkflowImplementationOptions getWorkflowImplementationOptions();

  /**
   * @return Deserialized completion result of the last cron workflow run
   */
  @Nullable
  <R> R getLastCompletionResult(Class<R> resultClass, Type resultType);

  /**
   * @return the list of configured context propagators
   */
  List<ContextPropagator> getContextPropagators();

  /**
   * Returns all current contexts being propagated by a {@link
   * io.temporal.common.context.ContextPropagator}. The key is the {@link
   * ContextPropagator#getName()} and the value is the object returned by {@link
   * ContextPropagator#getCurrentContext()}
   */
  Map<String, Object> getPropagatedContexts();
}
