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

package io.temporal.workflow.shared;

import static org.junit.Assert.assertEquals;

import io.temporal.activity.Activity;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ControlledActivityImpl implements TestActivities.NoArgsReturnsStringActivity {

  public enum Outcome {
    FAIL,
    SLEEP,
    COMPLETE
  }

  private final List<Outcome> outcomes;
  private final int expectedAttempts;
  private final long secondsToSleep;

  private volatile int lastAttempt = 0;

  public ControlledActivityImpl(List<Outcome> outcomes, int expectedAttempts, long secondsToSleep) {
    this.outcomes = outcomes;
    this.expectedAttempts = expectedAttempts;
    this.secondsToSleep = secondsToSleep;
  }

  @Override
  public String execute() {
    lastAttempt = Activity.getExecutionContext().getInfo().getAttempt();
    Outcome outcome = outcomes.get((lastAttempt - 1) % outcomes.size());
    switch (outcome) {
      case FAIL:
        throw new RuntimeException("intentional failure");
      case SLEEP:
        try {
          Thread.sleep(TimeUnit.SECONDS.toMillis(secondsToSleep));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException(e);
        }
        return "done";
      case COMPLETE:
        return "done";
      default:
        throw new IllegalArgumentException("Unexpected outcome: " + outcome);
    }
  }

  public void verifyAttempts() {
    assertEquals(
        "Amount of attempts performed is different from the expected",
        expectedAttempts,
        lastAttempt);
  }

  public int getLastAttempt() {
    return lastAttempt;
  }
}
