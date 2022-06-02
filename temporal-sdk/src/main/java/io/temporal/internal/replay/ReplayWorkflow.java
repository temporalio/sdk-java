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

import io.temporal.api.common.v1.Payloads;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.query.v1.WorkflowQuery;
import io.temporal.worker.WorkflowImplementationOptions;
import java.util.Optional;

public interface ReplayWorkflow {

  void start(HistoryEvent event, ReplayWorkflowContext context);

  /** Handle an external signal event. */
  void handleSignal(String signalName, Optional<Payloads> input, long eventId);

  boolean eventLoop();

  /**
   * @return null means no output yet
   */
  Optional<Payloads> getOutput();

  void cancel(String reason);

  void close();

  /**
   * Called after all history is replayed and workflow cannot make any progress if workflow task is
   * a query.
   *
   * @param query arguments
   * @return query result
   */
  Optional<Payloads> query(WorkflowQuery query);

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

  WorkflowImplementationOptions getWorkflowImplementationOptions();
}
