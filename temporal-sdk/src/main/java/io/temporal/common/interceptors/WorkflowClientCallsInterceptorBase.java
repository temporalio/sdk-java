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
  public WorkflowSignalOutput signal(WorkflowSignalInput input) {
    return next.signal(input);
  }

  @Override
  public WorkflowSignalWithStartOutput signalWithStart(WorkflowSignalWithStartInput input) {
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

  @Override
  public <R> QueryOutput<R> query(QueryInput<R> input) {
    return next.query(input);
  }

  @Override
  public <R> UpdateOutput<R> update(UpdateInput<R> input) {
    return next.update(input);
  }

  @Override
  public <R> UpdateAsyncOutput<R> updateAsync(UpdateInput<R> input) {
    return next.updateAsync(input);
  }

  @Override
  public CancelOutput cancel(CancelInput input) {
    return next.cancel(input);
  }

  @Override
  public TerminateOutput terminate(TerminateInput input) {
    return next.terminate(input);
  }
}
