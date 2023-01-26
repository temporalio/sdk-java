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

package io.temporal.spring.boot.autoconfigure.properties;

import java.util.Collection;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.springframework.boot.context.properties.ConstructorBinding;

public class WorkerProperties {
  private @Nonnull String taskQueue;
  private @Nullable String name;

  private @Nullable Collection<Class<?>> workflowClasses;

  private @Nullable Collection<String> activityBeans;

  @ConstructorBinding
  public WorkerProperties(
      @Nonnull String taskQueue,
      @Nullable String name,
      @Nullable Collection<Class<?>> workflowClasses,
      @Nullable Collection<String> activityBeans) {
    this.name = name;
    this.taskQueue = taskQueue;
    this.workflowClasses = workflowClasses;
    this.activityBeans = activityBeans;
  }

  @Nonnull
  public String getTaskQueue() {
    return taskQueue;
  }

  public void setTaskQueue(@Nonnull String taskQueue) {
    this.taskQueue = taskQueue;
  }

  @Nullable
  public String getName() {
    return name;
  }

  public void setName(@Nullable String name) {
    this.name = name;
  }

  @Nullable
  public Collection<Class<?>> getWorkflowClasses() {
    return workflowClasses;
  }

  public void setWorkflowClasses(@Nullable Collection<Class<?>> workflowClasses) {
    this.workflowClasses = workflowClasses;
  }

  @Nullable
  public Collection<String> getActivityBeans() {
    return activityBeans;
  }

  public void setActivityBeans(@Nullable Collection<String> activityBeans) {
    this.activityBeans = activityBeans;
  }
}
