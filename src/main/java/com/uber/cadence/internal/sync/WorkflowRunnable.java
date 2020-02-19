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

package com.uber.cadence.internal.sync;

import com.uber.cadence.WorkflowExecutionStartedEventAttributes;
import java.util.Objects;

class WorkflowRunnable implements Runnable {
  private final SyncDecisionContext context;
  private final SyncWorkflowDefinition workflow;
  private final WorkflowExecutionStartedEventAttributes attributes;

  private byte[] output;
  private boolean done;

  public WorkflowRunnable(
      SyncDecisionContext context,
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

      output = workflow.execute(attributes.getInput());
    } finally {
      done = true;
    }
  }

  public void cancel(String reason) {}

  public boolean isDone() {
    return done;
  }

  public byte[] getOutput() {
    return output;
  }

  public void close() {}

  public void processSignal(String signalName, byte[] input, long eventId) {
    workflow.processSignal(signalName, input, eventId);
  }

  public byte[] query(String type, byte[] args) {
    return context.query(type, args);
  }

  public void fireTimers() {
    if (context.hasTimersToFire()) {
      context.getRunner().executeInWorkflowThread("timers callback", () -> context.fireTimers());
    }
  }
}
