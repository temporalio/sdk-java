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
import java.util.Queue;

/**
 * Represents a step in a workflow which takes {@code <T>} as an input value from the previous step
 * and triggers registered callbacks with its output.
 *
 * <p>Provides several {@code add} functions, each works with a {@code proc} parameter of a specific
 * type which corresponds to different types of callbacks accepted by state machines.
 *
 * @param <T> input value of the step
 */
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

  /**
   * Adds a function {@code proc} to the processing of {@code <T>} and also creates a new step
   * {@code AsyncWorkflowBuilder<R>} that accepts types {@code <R>} as an input.
   *
   * <p>Use this method to pass {@code proc} that triggers a state machine function / transition
   * that accepts a callback with single value. The value this state machine passes to this callback
   * will be transferred to the next step {@code AsyncWorkflowBuilder<R>} provided as a return value
   * of this method.
   *
   * @param proc function that processes input {@code <T>} from the previous step and passes newly
   *     generated outputs (inputs for the next step) into a callback {@code Functions.Proc1<R>} to
   *     trigger the AsyncWorkflowBuilder returned from this function
   * @return the next step AsyncWorkflowBuilder<R>> that gets as an input whatever {@code proc}
   *     provides to its callback
   */
  <R> AsyncWorkflowBuilder<R> add1(Functions.Proc2<T, Functions.Proc1<R>> proc);

  /**
   * Adds a function {@code proc} to the callbacks and also creates a new step {@code
   * AsyncWorkflowBuilder<Pair<R1, R2>>} that accepts types {@code Pair<R1, R2>} as an input. The
   * {@code Pair<R1, R2>} usually represent a pair of {@code <Value, Error>}
   *
   * <p>Use this method to pass {@code proc} that triggers a state machine function / transition
   * that accepts a callback with two values (usually it's a pair of Value and Error). The pair this
   * state machine passes to this callback will be transferred to the next step {@code
   * AsyncWorkflowBuilder<Pair<R1, R2>>} provided as a return value of this method.
   *
   * @param proc function that processes input {@code <T>} from the previous step and passes newly
   *     generated output pairs (inputs for the next step) into a callback {@code
   *     Functions.Proc2<R1, R2>} to trigger the AsyncWorkflowBuilder returned from this function.
   * @return the next step AsyncWorkflowBuilder<Pair<R1, R2>> that gets as an input whatever {@code
   *     proc} provides to its callback
   */
  <R1, R2> AsyncWorkflowBuilder<Pair<R1, R2>> add2(
      Functions.Proc2<T, Functions.Proc2<R1, R2>> proc);

  /**
   * Adds a function {@code proc} to the processing of {@code <T>}
   *
   * <p>Use this method to pass {@code proc} that triggers a state machine function / transition
   * that doesn't accept any callbacks and only accepts an input value.
   *
   * @param proc function to add into callbacks
   * @return this
   */
  AsyncWorkflowBuilder<T> add(Functions.Proc1<T> proc);

  static <T> AsyncWorkflowBuilder newScheduler(Queue<Functions.Proc> dispatchQueue, T value) {
    AsyncWorkflowBuilderImpl scheduler = new AsyncWorkflowBuilderImpl(dispatchQueue);
    dispatchQueue.add(() -> scheduler.apply(value));
    return scheduler;
  }
}
