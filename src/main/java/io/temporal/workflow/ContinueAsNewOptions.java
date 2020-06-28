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

package io.temporal.workflow;

import java.time.Duration;

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

    private Builder() {}

    private Builder(ContinueAsNewOptions options) {
      if (options == null) {
        return;
      }
      this.workflowRunTimeout = options.workflowRunTimeout;
      this.taskQueue = options.taskQueue;
      this.workflowTaskTimeout = options.workflowTaskTimeout;
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

    public ContinueAsNewOptions build() {
      return new ContinueAsNewOptions(workflowRunTimeout, taskQueue, workflowTaskTimeout);
    }
  }

  private final Duration workflowRunTimeout;
  private final String taskQueue;
  private final Duration workflowTaskTimeout;

  public ContinueAsNewOptions(
      Duration workflowRunTimeout, String taskQueue, Duration workflowTaskTimeout) {
    this.workflowRunTimeout = workflowRunTimeout;
    this.taskQueue = taskQueue;
    this.workflowTaskTimeout = workflowTaskTimeout;
  }

  public Duration getWorkflowRunTimeout() {
    return workflowRunTimeout;
  }

  public String getTaskQueue() {
    return taskQueue;
  }

  public Duration getWorkflowTaskTimeout() {
    return workflowTaskTimeout;
  }
}
