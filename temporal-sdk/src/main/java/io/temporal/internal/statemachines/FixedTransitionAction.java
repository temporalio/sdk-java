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
import java.util.Collections;
import java.util.List;

/** Action that can transition to exactly one state. */
class FixedTransitionAction<State, Data> implements TransitionAction<State, Data> {

  final State state;

  final Functions.Proc1<Data> action;

  FixedTransitionAction(State state, Functions.Proc1<Data> action) {
    this.state = state;
    this.action = action;
  }

  @Override
  public String toString() {
    return "FixedTransitionAction{" + "toState=" + state + "}";
  }

  @Override
  public State apply(Data data) {
    action.apply(data);
    return state;
  }

  @Override
  public List<State> getAllowedStates() {
    return Collections.singletonList(state);
  }
}
