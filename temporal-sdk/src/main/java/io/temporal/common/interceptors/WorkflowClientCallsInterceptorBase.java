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

import java.util.concurrent.TimeoutException;

/** Convenience base class for {@link WorkflowClientCallsInterceptor} implementations. */
public class WorkflowClientCallsInterceptorBase implements WorkflowClientCallsInterceptor {

  private final WorkflowClientCallsInterceptor next;

  public WorkflowClientCallsInterceptorBase(WorkflowClientCallsInterceptor next) {
    this.next = next;
  }

  @Override
  public WorkflowStartOutput start(WorkflowStartInput input) {
    return next.start(input);
  }

  @Override
  public void signal(WorkflowSignalInput input) {
    next.signal(input);
  }

  @Override
  public WorkflowStartOutput signalWithStart(WorkflowStartWithSignalInput input) {
    return next.signalWithStart(input);
  }

  @Override
  public <R> GetResultOutput<R> getResult(GetResultInput<R> input) throws TimeoutException {
    return next.getResult(input);
  }

  @Override
  public <R> GetResultAsyncOutput<R> getResultAsync(GetResultInput<R> input) {
    return next.getResultAsync(input);
  }
}
