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

package com.uber.cadence.workflow.samples;

import com.uber.cadence.workflow.CompletablePromise;
import com.uber.cadence.workflow.Workflow;
import com.uber.cadence.workflow.WorkflowTest;

public class ReceiveSignalObject_ChildWorkflowImpl implements ReceiveSignalObject_ChildWorkflow {
      private String receivedSignal = "Initial State";
      // Keep workflow open so that we can send signal
      CompletablePromise<Void> promise = Workflow.newPromise();
      @Override
      public String execute() {
          promise.get();
          return receivedSignal;
      }

      @Override
      public void signal(Signal arg) {
          receivedSignal = arg.value;
      }

    @Override
    public void close() {
        promise.complete(null);
    }
  }
