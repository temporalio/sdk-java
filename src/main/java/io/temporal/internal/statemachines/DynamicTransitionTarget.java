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

import java.util.Arrays;
import java.util.List;

class DynamicTransitionTarget<State, Data> implements TransitionTarget<State, Data> {

  final DynamicCallback<State, Data> callback;
  State[] expectedStates;
  State state;

  DynamicTransitionTarget(State[] expectedStates, DynamicCallback<State, Data> callback) {
    this.expectedStates = expectedStates;
    this.callback = callback;
  }

  @Override
  public String toString() {
    return "DynamicTransitionDestination{" + "state=" + state + ", callback=" + callback + '}';
  }

  @Override
  public State apply(Data data) {
    state = callback.apply(data);
    for (State s : expectedStates) {
      if (s.equals(state)) {
        return state;
      }
    }
    throw new IllegalStateException(
        state + " is not expected. Expected states are: " + Arrays.toString(expectedStates));
  }

  @Override
  public List<State> getAllowedStates() {
    return Arrays.asList(expectedStates);
  }
}
