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

package io.temporal.common;

import static org.junit.Assert.assertEquals;

import java.time.Duration;
import org.junit.Test;

public class RetryOptionsTest {

  @Test
  public void mergePrefersTheParameter() {
    RetryOptions o1 =
        RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofSeconds(1))
            .validateBuildWithDefaults();
    RetryOptions o2 =
        RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofSeconds(2))
            .validateBuildWithDefaults();

    assertEquals(Duration.ofSeconds(2), o1.merge(o2).getInitialInterval());
  }

  @Test(expected = IllegalStateException.class)
  public void maximumIntervalCantBeLessThanInitial() {
    RetryOptions.newBuilder()
        .setInitialInterval(Duration.ofSeconds(5))
        .setMaximumInterval(Duration.ofSeconds(1))
        .validateBuildWithDefaults();
  }
}
