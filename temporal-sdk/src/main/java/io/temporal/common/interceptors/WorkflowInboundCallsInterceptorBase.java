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

/** Convenience base class for WorkflowInboundCallsInterceptor implementations. */
public class WorkflowInboundCallsInterceptorBase implements WorkflowInboundCallsInterceptor {
  private final WorkflowInboundCallsInterceptor next;

  public WorkflowInboundCallsInterceptorBase(WorkflowInboundCallsInterceptor next) {
    this.next = next;
  }

  @Override
  public void init() {
    next.init();
  }

  @Override
  public WorkflowOutput execute(WorkflowInput input) {
    return next.execute(input);
  }

  @Override
  public void handleSignal(SignalInput input) {
    next.handleSignal(input);
  }

  @Override
  public QueryOutput handleQuery(QueryInput input) {
    return next.handleQuery(input);
  }
}
