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

import io.temporal.api.common.v1.Payloads;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import java.util.Objects;
import java.util.Optional;

class WorkflowExecuteRunnable implements Runnable {
  private final SyncWorkflowContext context;
  private final SyncWorkflowDefinition workflow;
  private final WorkflowExecutionStartedEventAttributes attributes;

  private Optional<Payloads> output = Optional.empty();
  private boolean done;

  public WorkflowExecuteRunnable(
      SyncWorkflowContext context,
      SyncWorkflowDefinition workflow,
      WorkflowExecutionStartedEventAttributes attributes) {
    Objects.requireNonNull(context);
    Objects.requireNonNull(workflow);
    Objects.requireNonNull(attributes);
    this.context = context;
    this.workflow = workflow;
    this.attributes = attributes;
  }

  @Override
  public void run() {
    try {
      Optional<Payloads> input =
          attributes.hasInput() ? Optional.of(attributes.getInput()) : Optional.empty();
      output = workflow.execute(input);
    } finally {
      done = true;
    }
  }

  public void cancel(String reason) {}

  public boolean isDone() {
    return done;
  }

  public Optional<Payloads> getOutput() {
    return output;
  }

  public void close() {}

  public void processSignal(String signalName, Optional<Payloads> input, long eventId) {
    context.signal(signalName, input, eventId);
  }

  public Optional<Payloads> query(String type, Optional<Payloads> args) {
    return context.query(type, args);
  }
}
