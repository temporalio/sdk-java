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

package io.temporal.client;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.workflow.Functions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

final class SignalWithStartBatchRequest implements BatchRequest {

  private final List<Functions.Proc> requests = new ArrayList<>();

  private WorkflowStub stub;
  private String signalName;
  private Object[] signalArgs;
  private Object[] startArgs;
  private final AtomicBoolean invoked = new AtomicBoolean();

  WorkflowExecution invoke() {
    if (!invoked.compareAndSet(false, true)) {
      throw new IllegalStateException(
          "A batch instance can be used only for a single signalWithStart call");
    }
    WorkflowInvocationHandler.initAsyncInvocation(
        WorkflowInvocationHandler.InvocationType.SIGNAL_WITH_START, this);
    try {
      for (Functions.Proc request : requests) {
        request.apply();
      }
      return signalWithStart();
    } finally {
      WorkflowInvocationHandler.closeAsyncInvocation();
    }
  }

  private WorkflowExecution signalWithStart() {
    return stub.signalWithStart(signalName, signalArgs, startArgs);
  }

  void signal(WorkflowStub stub, String signalName, Object[] args) {
    setStub(stub);
    this.signalName = signalName;
    this.signalArgs = args;
  }

  void start(WorkflowStub stub, Object[] args) {
    setStub(stub);
    this.startArgs = args;
  }

  private void setStub(WorkflowStub stub) {
    if (this.stub != null && stub != this.stub) {
      throw new IllegalArgumentException(
          "SignalWithStart Batch invoked on different workflow stubs");
    }
    this.stub = stub;
  }

  @Override
  public void add(Functions.Proc request) {
    requests.add(request);
  }

  @Override
  public <A1> void add(Functions.Proc1<A1> request, A1 arg1) {
    add(() -> request.apply(arg1));
  }

  @Override
  public <A1, A2> void add(Functions.Proc2<A1, A2> request, A1 arg1, A2 arg2) {
    add(() -> request.apply(arg1, arg2));
  }

  @Override
  public <A1, A2, A3> void add(Functions.Proc3<A1, A2, A3> request, A1 arg1, A2 arg2, A3 arg3) {
    add(() -> request.apply(arg1, arg2, arg3));
  }

  @Override
  public <A1, A2, A3, A4> void add(
      Functions.Proc4<A1, A2, A3, A4> request, A1 arg1, A2 arg2, A3 arg3, A4 arg4) {
    add(() -> request.apply(arg1, arg2, arg3, arg4));
  }

  @Override
  public <A1, A2, A3, A4, A5> void add(
      Functions.Proc5<A1, A2, A3, A4, A5> request, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5) {
    add(() -> request.apply(arg1, arg2, arg3, arg4, arg5));
  }

  @Override
  public <A1, A2, A3, A4, A5, A6> void add(
      Functions.Proc6<A1, A2, A3, A4, A5, A6> request,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6) {
    add(() -> request.apply(arg1, arg2, arg3, arg4, arg5, arg6));
  }

  @Override
  public void add(Functions.Func<?> request) {
    add(
        () -> {
          request.apply();
        });
  }

  @Override
  public <A1> void add(Functions.Func1<A1, ?> request, A1 arg1) {
    add(() -> request.apply(arg1));
  }

  @Override
  public <A1, A2> void add(Functions.Func2<A1, A2, ?> request, A1 arg1, A2 arg2) {
    add(() -> request.apply(arg1, arg2));
  }

  @Override
  public <A1, A2, A3> void add(Functions.Func3<A1, A2, A3, ?> request, A1 arg1, A2 arg2, A3 arg3) {
    add(() -> request.apply(arg1, arg2, arg3));
  }

  @Override
  public <A1, A2, A3, A4> void add(
      Functions.Func4<A1, A2, A3, A4, ?> request, A1 arg1, A2 arg2, A3 arg3, A4 arg4) {
    add(() -> request.apply(arg1, arg2, arg3, arg4));
  }

  @Override
  public <A1, A2, A3, A4, A5> void add(
      Functions.Func5<A1, A2, A3, A4, A5, ?> request, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5) {
    add(() -> request.apply(arg1, arg2, arg3, arg4, arg5));
  }

  @Override
  public <A1, A2, A3, A4, A5, A6> void add(
      Functions.Func6<A1, A2, A3, A4, A5, A6, ?> request,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6) {
    add(() -> request.apply(arg1, arg2, arg3, arg4, arg5, arg6));
  }
}
