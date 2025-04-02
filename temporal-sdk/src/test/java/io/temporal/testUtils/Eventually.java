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

package io.temporal.testUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

public class Eventually {
  public static void assertEventually(Duration timeout, Runnable command) {
    assertEventually(
        timeout,
        () -> {
          command.run();
          return null;
        });
  }

  public static <T> T assertEventually(Duration timeout, Supplier<T> command) {
    final Instant start = Instant.now();
    final Instant deadline = start.plus(timeout);

    while (true) {
      try {
        return command.get();
      } catch (Throwable t) {
        if (Instant.now().isAfter(deadline)) {
          throw t;
        }
        // Try again after a short nap
        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException(e);
        }
      }
    }
  }
}
