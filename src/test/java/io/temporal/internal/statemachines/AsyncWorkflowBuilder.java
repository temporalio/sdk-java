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

package io.temporal.internal.statemachines;

import io.temporal.workflow.Functions;
import java.util.Queue;

interface AsyncWorkflowBuilder<T> {

  class Pair<T1, T2> {
    public final T1 t1;
    public final T2 t2;

    public Pair(T1 t1, T2 t2) {
      this.t1 = t1;
      this.t2 = t2;
    }

    public T1 getT1() {
      return t1;
    }

    public T2 getT2() {
      return t2;
    }
  }

  <R> AsyncWorkflowBuilder<R> add1(Functions.Proc2<T, Functions.Proc1<R>> proc);

  <R1, R2> AsyncWorkflowBuilder<Pair<R1, R1>> add2(
      Functions.Proc2<T, Functions.Proc2<R1, R2>> proc);

  void add(Functions.Proc1<T> proc);

  static <T> AsyncWorkflowBuilder newScheduler(Queue<Functions.Proc> dispatchQueue, T value) {
    AsyncWorkflowBuilderImpl scheduler = new AsyncWorkflowBuilderImpl(dispatchQueue);
    dispatchQueue.add(() -> scheduler.apply(value));
    return scheduler;
  }
}
