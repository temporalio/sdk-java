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
import java.util.Collections;
import java.util.List;

class FixedTransitionTarget<State, Data> implements TransitionTarget<State, Data> {

  final State state;

  final Functions.Proc1<Data> callback;

  FixedTransitionTarget(State state, Functions.Proc1<Data> callback) {
    this.state = state;
    this.callback = callback;
  }

  @Override
  public String toString() {
    return "TransitionDestination{" + "state=" + state + ", callback=" + callback + '}';
  }

  @Override
  public State apply(Data data) {
    callback.apply(data);
    return state;
  }

  @Override
  public List<State> getAllowedStates() {
    return Collections.singletonList(state);
  }
}
