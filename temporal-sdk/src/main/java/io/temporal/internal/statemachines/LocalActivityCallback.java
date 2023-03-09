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

import io.temporal.api.common.v1.Payloads;
import io.temporal.api.failure.v1.Failure;
import io.temporal.workflow.Functions;
import java.time.Duration;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@FunctionalInterface
public interface LocalActivityCallback
    extends Functions.Proc2<
        Optional<Payloads>, LocalActivityCallback.LocalActivityFailedException> {

  @Override
  void apply(Optional<Payloads> successOutput, LocalActivityFailedException exception);

  class LocalActivityFailedException extends RuntimeException {
    private final @Nonnull Failure failure;
    private final int lastAttempt;
    /**
     * If this is not null, code that processes this exception will schedule a workflow timer to
     * continue retrying the execution
     */
    private final @Nullable Duration backoff;

    public LocalActivityFailedException(
        @Nonnull Failure failure,
        long originalScheduledTimestamp,
        int lastAttempt,
        @Nullable Duration backoff) {
      this.failure = failure;
      this.lastAttempt = lastAttempt;
      this.backoff = backoff;
    }

    @Nonnull
    public Failure getFailure() {
      return failure;
    }

    public int getLastAttempt() {
      return lastAttempt;
    }

    @Nullable
    public Duration getBackoff() {
      return backoff;
    }
  }
}
