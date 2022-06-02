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

package io.temporal.internal.activity;

import io.temporal.activity.ManualActivityCompletionClient;
import io.temporal.failure.CanceledFailure;
import io.temporal.workflow.Functions;
import javax.annotation.Nonnull;

final class CompletionAwareManualCompletionClient implements ManualActivityCompletionClient {
  private final ManualActivityCompletionClient client;
  private final Functions.Proc completionHandle;

  CompletionAwareManualCompletionClient(
      ManualActivityCompletionClient client, Functions.Proc completionHandle) {
    this.client = client;
    this.completionHandle = completionHandle;
  }

  @Override
  public void complete(Object result) {
    try {
      client.complete(result);
    } finally {
      completionHandle.apply();
    }
  }

  @Override
  public void fail(@Nonnull Throwable failure) {
    try {
      client.fail(failure);
    } finally {
      completionHandle.apply();
    }
  }

  @Override
  public void recordHeartbeat(Object details) throws CanceledFailure {
    client.recordHeartbeat(details);
  }

  @Override
  public void reportCancellation(Object details) {
    try {
      client.reportCancellation(details);
    } finally {
      completionHandle.apply();
    }
  }
}
