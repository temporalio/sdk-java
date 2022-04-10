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

package io.temporal.workflow.interceptors;

import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptorBase;
import java.util.function.Function;

public class SignalWorkflowOutboundCallsInterceptor extends WorkflowOutboundCallsInterceptorBase {
  private final Function<Object[], Object[]> overrideArgs;
  private final Function<String, String> overrideSignalName;

  public SignalWorkflowOutboundCallsInterceptor(
      Function<Object[], Object[]> overrideArgs,
      Function<String, String> overrideSignalName,
      WorkflowOutboundCallsInterceptor next) {
    super(next);
    this.overrideArgs = overrideArgs;
    this.overrideSignalName = overrideSignalName;
  }

  @Override
  public SignalExternalOutput signalExternalWorkflow(SignalExternalInput input) {
    Object[] args = input.getArgs();
    if (args != null && args.length > 0) {
      args = new Object[] {"corrupted signal"};
    }
    return super.signalExternalWorkflow(
        new SignalExternalInput(
            input.getExecution(),
            overrideSignalName.apply(input.getSignalName()),
            overrideArgs.apply(args)));
  }
}
