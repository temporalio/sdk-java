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

package io.temporal.internal.statemachines;

import io.temporal.workflow.Functions;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

class AsyncWorkflowBuilderImpl<T> implements AsyncWorkflowBuilder<T> {

  private final Queue<Functions.Proc> scheduled;

  private final List<Functions.Proc1<T>> callbacks = new ArrayList<>();

  private final Functions.Proc1<T> callback =
      (result) -> {
        for (Functions.Proc1<T> callback : callbacks) {
          schedule(() -> callback.apply(result));
        }
      };

  void apply(T value) {
    callback.apply(value);
  }

  private void schedule(Functions.Proc proc) {
    scheduled.add(proc);
  }

  AsyncWorkflowBuilderImpl(Queue<Functions.Proc> scheduled) {
    this.scheduled = scheduled;
  }

  @Override
  public <R> AsyncWorkflowBuilder<R> add1(Functions.Proc2<T, Functions.Proc1<R>> proc) {
    AsyncWorkflowBuilderImpl<R> scheduler = new AsyncWorkflowBuilderImpl<>(scheduled);
    callbacks.add((value) -> schedule(() -> proc.apply(value, scheduler.callback)));
    return scheduler;
  }

  @Override
  public <R1, R2> AsyncWorkflowBuilder<Pair<R1, R2>> add2(
      Functions.Proc2<T, Functions.Proc2<R1, R2>> proc) {
    AsyncWorkflowBuilderImpl<Pair<R1, R2>> scheduler = new AsyncWorkflowBuilderImpl<>(scheduled);
    callbacks.add(
        (value) ->
            schedule(
                () -> proc.apply(value, (t1, t2) -> scheduler.callback.apply(new Pair<>(t1, t2)))));
    return scheduler;
  }

  @Override
  public AsyncWorkflowBuilder<T> add(Functions.Proc1<T> proc) {
    callbacks.add((result) -> schedule(() -> proc.apply(result)));
    return this;
  }
}
