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

import io.temporal.activity.ActivityInfo;
import io.temporal.common.Experimental;
import java.util.Objects;
import javax.annotation.Nonnull;

@Experimental
public class ActivitySerializationContext implements SerializationContext {
  private final @Nonnull String namespace;
  private final @Nonnull String workflowId;
  private final @Nonnull String workflowType;
  private final @Nonnull String activityType;
  private final @Nonnull String activityTaskQueue;
  private final boolean local;

  public ActivitySerializationContext(
      @Nonnull String namespace,
      @Nonnull String workflowId,
      @Nonnull String workflowType,
      @Nonnull String activityType,
      @Nonnull String activityTaskQueue,
      boolean local) {
    this.namespace = Objects.requireNonNull(namespace);
    this.workflowId = Objects.requireNonNull(workflowId);
    this.workflowType = Objects.requireNonNull(workflowType);
    this.activityType = Objects.requireNonNull(activityType);
    this.activityTaskQueue = Objects.requireNonNull(activityTaskQueue);
    this.local = local;
  }

  public ActivitySerializationContext(ActivityInfo info) {
    this(
        info.getNamespace(),
        info.getWorkflowId(),
        info.getWorkflowType(),
        info.getActivityType(),
        info.getActivityTaskQueue(),
        info.isLocal());
  }

  @Nonnull
  public String getNamespace() {
    return namespace;
  }

  @Nonnull
  public String getWorkflowId() {
    return workflowId;
  }

  @Nonnull
  public String getWorkflowType() {
    return workflowType;
  }

  @Nonnull
  public String getActivityType() {
    return activityType;
  }

  @Nonnull
  public String getActivityTaskQueue() {
    return activityTaskQueue;
  }

  public boolean isLocal() {
    return local;
  }
}
