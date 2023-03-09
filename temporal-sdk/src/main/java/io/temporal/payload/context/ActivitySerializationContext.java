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

package io.temporal.payload.context;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class ActivitySerializationContext implements SerializationContext {
  private final @Nonnull String workflowId;
  private final @Nonnull String workflowTypeName;
  private final @Nonnull String workflowTaskQueue;
  private final @Nonnull String activityTypeName;
  private final @Nullable String activityTaskQueue;

  public ActivitySerializationContext(
      @Nonnull String workflowId,
      @Nonnull String workflowType,
      @Nonnull String workflowTaskQueue,
      @Nonnull String activityTypeName,
      @Nullable String activityTaskQueue) {
    this.workflowId = workflowId;
    this.workflowTypeName = workflowType;
    this.workflowTaskQueue = workflowTaskQueue;
    this.activityTypeName = activityTypeName;
    this.activityTaskQueue = activityTaskQueue;
  }

  @Nonnull
  public String getWorkflowId() {
    return workflowId;
  }

  @Nonnull
  public String getWorkflowTypeName() {
    return workflowTypeName;
  }

  @Nonnull
  public String getWorkflowTaskQueue() {
    return workflowTaskQueue;
  }

  @Nonnull
  public String getActivityTypeName() {
    return activityTypeName;
  }

  @Nullable
  public String getActivityTaskQueue() {
    return activityTaskQueue;
  }
}
