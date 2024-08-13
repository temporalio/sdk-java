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

package io.temporal.internal.sync;

import io.temporal.workflow.HandlerUnfinishedPolicy;

public class SignalHandlerInfo {
  private final long eventId;
  private final String name;
  private final HandlerUnfinishedPolicy policy;

  public SignalHandlerInfo(long eventId, String name, HandlerUnfinishedPolicy policy) {
    this.eventId = eventId;
    this.name = name;
    this.policy = policy;
  }

  public String getName() {
    return name;
  }

  public HandlerUnfinishedPolicy getPolicy() {
    return policy;
  }

  @Override
  public String toString() {
    return "SignalHandlerInfo{"
        + "eventId="
        + eventId
        + ", name='"
        + name
        + '\''
        + ", policy="
        + policy
        + '}';
  }
}
