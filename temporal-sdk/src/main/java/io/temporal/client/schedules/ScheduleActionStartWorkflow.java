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

package io.temporal.client.schedules;

import io.temporal.client.WorkflowOptions;
import io.temporal.common.converter.EncodedValues;
import io.temporal.common.interceptors.Header;
import io.temporal.common.metadata.POJOWorkflowInterfaceMetadata;

/** Schedule action to start a workflow. */
public final class ScheduleActionStartWorkflow extends ScheduleAction {
  public static ScheduleActionStartWorkflow.Builder newBuilder() {
    return new ScheduleActionStartWorkflow.Builder();
  }

  public static ScheduleActionStartWorkflow.Builder newBuilder(
      ScheduleActionStartWorkflow options) {
    return new ScheduleActionStartWorkflow.Builder(options);
  }

  public static class Builder {
    private String workflowType;
    private WorkflowOptions options;
    private Header header;
    private EncodedValues arguments;

    private Builder() {}

    private Builder(ScheduleActionStartWorkflow options) {
      if (options == null) {
        return;
      }
      this.workflowType = options.workflowType;
      this.options = options.options;
      this.header = options.header;
      this.arguments = options.arguments;
    }

    /** Set the name of the workflow type */
    public Builder setWorkflowType(String workflowType) {
      this.workflowType = workflowType;
      return this;
    }

    /** Set the workflow type. workflowInterface must implement a WorkflowInterface */
    public <T> Builder setWorkflowType(Class<T> workflowInterface) {
      POJOWorkflowInterfaceMetadata workflowMetadata =
          POJOWorkflowInterfaceMetadata.newInstance(workflowInterface, true);
      this.workflowType = workflowMetadata.getWorkflowType().get();
      return this;
    }

    /**
     * Set the workflow options to use when starting a workflow action.
     *
     * @note ID and TaskQueue are required. Some options like ID reuse policy, cron schedule, and
     *     start signal cannot be set or an error will occur.
     */
    public Builder setOptions(WorkflowOptions options) {
      this.options = options;
      return this;
    }

    /** Set the headers sent with each workflow scheduled. */
    public Builder setHeader(Header header) {
      this.header = header;
      return this;
    }

    /** Set the workflow arguments to use when starting a workflow action. */
    public Builder setRawArguments(EncodedValues values) {
      this.arguments = values;
      return this;
    }

    /** Set the workflow arguments to use when starting a workflow action. */
    public Builder setArguments(Object... arguments) {
      this.arguments = new EncodedValues(arguments);
      return this;
    }

    public ScheduleActionStartWorkflow build() {
      return new ScheduleActionStartWorkflow(
          workflowType, options, header == null ? Header.empty() : header, arguments);
    }
  }

  private final String workflowType;
  private final WorkflowOptions options;
  private final Header header;
  private final EncodedValues arguments;

  private ScheduleActionStartWorkflow(
      String workflowType, WorkflowOptions options, Header header, EncodedValues arguments) {
    this.workflowType = workflowType;
    this.options = options;
    this.header = header;
    this.arguments = arguments;
  }

  /**
   * Get the workflow type name.
   *
   * @return the workflow type name.
   */
  public String getWorkflowType() {
    return workflowType;
  }

  /**
   * Get the workflow options used. Note {@link WorkflowOptions#getMemo()} every value here is an
   * instance of {@link EncodedValues}. To access, call {@link EncodedValues#get} with an index of
   * 0.
   *
   * @return workflow options used for the scheduled workflows.
   */
  public WorkflowOptions getOptions() {
    return options;
  }

  /**
   * Get the headers that will be sent with each workflow scheduled.
   *
   * @return headers to be sent
   */
  public Header getHeader() {
    return header;
  }

  /**
   * Arguments for the workflow.
   *
   * @return the arguments used for the scheduled workflows.
   */
  public EncodedValues getArguments() {
    return arguments;
  }
}
