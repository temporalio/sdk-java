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

import com.google.common.base.Preconditions;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import javax.annotation.Nonnull;

public class WorkflowTask {
  private final @Nonnull PollWorkflowTaskQueueResponse response;
  private final long taskPollTimeNs;

  public WorkflowTask(@Nonnull PollWorkflowTaskQueueResponse response, long taskPollTimeNs) {
    this.response = Preconditions.checkNotNull(response);
    this.taskPollTimeNs = taskPollTimeNs;
  }

  @Nonnull
  public PollWorkflowTaskQueueResponse getResponse() {
    return response;
  }

  public long getTaskPollTimeNs() {
    return taskPollTimeNs;
  }
}
