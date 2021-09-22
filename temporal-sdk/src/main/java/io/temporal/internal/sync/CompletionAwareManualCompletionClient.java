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

package io.temporal.internal.sync;

import io.temporal.activity.ManualActivityCompletionClient;
import io.temporal.failure.CanceledFailure;
import io.temporal.workflow.Functions;

public final class CompletionAwareManualCompletionClient implements ManualActivityCompletionClient {
  private final ManualActivityCompletionClient client;
  private final Functions.Proc completionHandle;

  public CompletionAwareManualCompletionClient(
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
  public void fail(Throwable failure) {
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
