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

import java.util.Objects;

class Transition<State, Event> {

  final State from;
  final Event event;

  public Transition(State from, Event event) {
    this.from = Objects.requireNonNull(from);
    this.event = Objects.requireNonNull(event);
  }

  public State getFrom() {
    return from;
  }

  public Event getExplicitEvent() {
    return event;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Transition<?, ?> that = (Transition<?, ?>) o;
    return com.google.common.base.Objects.equal(from, that.from)
        && com.google.common.base.Objects.equal(event, that.event);
  }

  @Override
  public int hashCode() {
    return com.google.common.base.Objects.hashCode(from, event);
  }

  @Override
  public String toString() {
    return from + "->" + event;
  }
}
