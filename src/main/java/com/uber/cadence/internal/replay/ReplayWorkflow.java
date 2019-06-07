/*
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

package com.uber.cadence.internal.replay;

import com.uber.cadence.HistoryEvent;
import com.uber.cadence.WorkflowQuery;
import com.uber.cadence.internal.worker.WorkflowExecutionException;
import com.uber.cadence.worker.WorkflowImplementationOptions;

public interface ReplayWorkflow {

  void start(HistoryEvent event, DecisionContext context);

  /** Handle an external signal event. */
  void handleSignal(String signalName, byte[] input, long eventId);

  boolean eventLoop() throws Throwable;

  /** @return null means no output yet */
  byte[] getOutput();

  void cancel(String reason);

  void close();

  /**
   * @return time at which workflow can make progress. For example when {@link
   *     com.uber.cadence.workflow.Workflow#sleep(long)} expires.
   */
  long getNextWakeUpTime();

  /**
   * Called after all history is replayed and workflow cannot make any progress if decision task is
   * a query.
   *
   * @param query
   */
  byte[] query(WorkflowQuery query);

  /**
   * Convert exception that happened in the framework code to the format that ReplayWorkflow
   * implementation understands. The framework code is not aware of DataConverter so this is working
   * around this layering.
   *
   * @param failure Unexpected failure cause
   * @return Serialized failure
   */
  WorkflowExecutionException mapUnexpectedException(Exception failure);

  WorkflowExecutionException mapError(Error failure);

  WorkflowImplementationOptions getWorkflowImplementationOptions();
}
