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

package io.temporal.internal.worker;

import io.temporal.api.workflowservice.v1.PollNexusTaskQueueResponseOrBuilder;
import io.temporal.worker.tuning.SlotPermit;
import io.temporal.workflow.Functions;
import javax.annotation.Nonnull;

public final class NexusTask {
  private final @Nonnull PollNexusTaskQueueResponseOrBuilder response;
  private final @Nonnull SlotPermit permit;
  private final @Nonnull Functions.Proc completionCallback;

  public NexusTask(
      @Nonnull PollNexusTaskQueueResponseOrBuilder response,
      @Nonnull SlotPermit permit,
      @Nonnull Functions.Proc completionCallback) {
    this.response = response;
    this.permit = permit;
    this.completionCallback = completionCallback;
  }

  @Nonnull
  public PollNexusTaskQueueResponseOrBuilder getResponse() {
    return response;
  }

  /**
   * Completion handle function that must be called by the handler whenever the task processing is
   * completed.
   */
  @Nonnull
  public Functions.Proc getCompletionCallback() {
    return completionCallback;
  }

  @Nonnull
  public SlotPermit getPermit() {
    return permit;
  }
}
