/*
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

package com.uber.cadence.internal.replay;

import java.util.Objects;

class DecisionId {

  private final DecisionTarget decisionTarget;

  private final long decisionEventId;

  DecisionId(DecisionTarget decisionTarget, long decisionEventId) {
    this.decisionEventId = decisionEventId;
    this.decisionTarget = Objects.requireNonNull(decisionTarget);
  }

  DecisionTarget getDecisionTarget() {
    return decisionTarget;
  }

  long getDecisionEventId() {
    return decisionEventId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || !(o instanceof DecisionId)) {
      return false;
    }

    DecisionId that = (DecisionId) o;

    if (decisionEventId != that.decisionEventId) {
      return false;
    }
    return decisionTarget == that.decisionTarget;
  }

  @Override
  public int hashCode() {
    int result = decisionTarget.hashCode();
    result = 31 * result + (int) (decisionEventId ^ (decisionEventId >>> 32));
    return result;
  }

  @Override
  public String toString() {
    return "DecisionId{"
        + "decisionTarget="
        + decisionTarget
        + ", decisionEventId="
        + decisionEventId
        + '}';
  }
}
