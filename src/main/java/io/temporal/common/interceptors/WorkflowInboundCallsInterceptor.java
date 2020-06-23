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

package io.temporal.common.interceptors;

/**
 * Intercepts calls to the workflow execution. Executes under workflow context. So all the
 * restrictions on the workflow code should be obeyed.
 */
public interface WorkflowInboundCallsInterceptor {
  /**
   * Called when workflow class is instantiated.
   *
   * @param outboundCalls interceptor for calls that workflow makes to the SDK
   */
  void init(WorkflowOutboundCallsInterceptor outboundCalls);

  /**
   * Called when workflow main method is called.
   *
   * @return result of the workflow execution.
   */
  Object execute(Object[] arguments);

  /** Called when signal is delivered to the workflow instance. */
  void processSignal(String signalName, Object[] arguments, long EventId);
}
