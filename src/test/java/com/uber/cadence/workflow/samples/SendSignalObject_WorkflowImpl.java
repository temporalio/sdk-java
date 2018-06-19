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

import com.uber.cadence.workflow.Async;
import com.uber.cadence.workflow.Promise;
import com.uber.cadence.workflow.Workflow;
import com.uber.cadence.workflow.samples.ReceiveSignalObject_ChildWorkflow;
import com.uber.cadence.workflow.samples.SendSignalObject_Workflow;
import com.uber.cadence.workflow.samples.Signal;

public class SendSignalObject_WorkflowImpl implements SendSignalObject_Workflow {
  @Override
  public String execute() {
      ReceiveSignalObject_ChildWorkflow child =
              Workflow.newChildWorkflowStub(ReceiveSignalObject_ChildWorkflow.class);
      Promise<String> greeting = Async.function(child::execute);
      Signal sig = new Signal();
      sig.value = "Hello World";
      child.signal(sig);
      child.close();
      return greeting.get();
  }
}
