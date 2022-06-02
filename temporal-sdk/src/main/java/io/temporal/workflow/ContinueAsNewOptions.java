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

package io.temporal.workflow;

import io.temporal.common.context.ContextPropagator;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * This class contain overrides for continueAsNew call. Every field can be null and it means that
 * the value of the option should be taken from the originating workflow run.
 */
public final class ContinueAsNewOptions {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(ContinueAsNewOptions options) {
    return new Builder(options);
  }

  public static ContinueAsNewOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final ContinueAsNewOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = ContinueAsNewOptions.newBuilder().build();
  }

  public static final class Builder {

    private Duration workflowRunTimeout;
    private String taskQueue;
    private Duration workflowTaskTimeout;
    private Map<String, Object> memo;
    private Map<String, Object> searchAttributes;
    private List<ContextPropagator> contextPropagators;

    private Builder() {}

    private Builder(ContinueAsNewOptions options) {
      if (options == null) {
        return;
      }
      this.workflowRunTimeout = options.workflowRunTimeout;
      this.taskQueue = options.taskQueue;
      this.workflowTaskTimeout = options.workflowTaskTimeout;
      this.memo = options.getMemo();
      this.searchAttributes = options.getSearchAttributes();
      this.contextPropagators = options.getContextPropagators();
    }

    public Builder setWorkflowRunTimeout(Duration workflowRunTimeout) {
      this.workflowRunTimeout = workflowRunTimeout;
      return this;
    }

    public Builder setTaskQueue(String taskQueue) {
      this.taskQueue = taskQueue;
      return this;
    }

    public Builder setWorkflowTaskTimeout(Duration workflowTaskTimeout) {
      this.workflowTaskTimeout = workflowTaskTimeout;
      return this;
    }

    public Builder setMemo(Map<String, Object> memo) {
      this.memo = memo;
      return this;
    }

    public Builder setSearchAttributes(Map<String, Object> searchAttributes) {
      this.searchAttributes = searchAttributes;
      return this;
    }

    public Builder setContextPropagators(List<ContextPropagator> contextPropagators) {
      this.contextPropagators = contextPropagators;
      return this;
    }

    public ContinueAsNewOptions build() {
      return new ContinueAsNewOptions(
          workflowRunTimeout,
          taskQueue,
          workflowTaskTimeout,
          memo,
          searchAttributes,
          contextPropagators);
    }
  }

  private final @Nullable Duration workflowRunTimeout;
  private final @Nullable String taskQueue;
  private final @Nullable Duration workflowTaskTimeout;
  private final @Nullable Map<String, Object> memo;
  private final @Nullable Map<String, Object> searchAttributes;
  private final @Nullable List<ContextPropagator> contextPropagators;

  public ContinueAsNewOptions(
      @Nullable Duration workflowRunTimeout,
      @Nullable String taskQueue,
      @Nullable Duration workflowTaskTimeout,
      @Nullable Map<String, Object> memo,
      @Nullable Map<String, Object> searchAttributes,
      @Nullable List<ContextPropagator> contextPropagators) {
    this.workflowRunTimeout = workflowRunTimeout;
    this.taskQueue = taskQueue;
    this.workflowTaskTimeout = workflowTaskTimeout;
    this.memo = memo;
    this.searchAttributes = searchAttributes;
    this.contextPropagators = contextPropagators;
  }

  public @Nullable Duration getWorkflowRunTimeout() {
    return workflowRunTimeout;
  }

  public @Nullable String getTaskQueue() {
    return taskQueue;
  }

  public @Nullable Duration getWorkflowTaskTimeout() {
    return workflowTaskTimeout;
  }

  public @Nullable Map<String, Object> getMemo() {
    return memo;
  }

  public @Nullable Map<String, Object> getSearchAttributes() {
    return searchAttributes;
  }

  public @Nullable List<ContextPropagator> getContextPropagators() {
    return contextPropagators;
  }
}
