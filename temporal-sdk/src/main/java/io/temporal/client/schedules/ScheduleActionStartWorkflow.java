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

import io.temporal.api.common.v1.Payloads;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.EncodedValues;
import io.temporal.common.interceptors.Header;
import io.temporal.common.metadata.POJOWorkflowInterfaceMetadata;
import java.util.Optional;

/** Schedule action to start a workflow. */
public final class ScheduleActionStartWorkflow implements ScheduleAction {
  public static ScheduleActionStartWorkflow.Builder newBuilder() {
    return new ScheduleActionStartWorkflow.Builder();
  }

  public static ScheduleActionStartWorkflow.Builder newBuilder(
      ScheduleActionStartWorkflow options) {
    return new ScheduleActionStartWorkflow.Builder(options);
  }

  public static ScheduleActionStartWorkflow getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final ScheduleActionStartWorkflow DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = ScheduleActionStartWorkflow.newBuilder().build();
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
   * Get the workflow options used.
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
  public EncodedValues getArgs() {
    return args;
  }

  private final String workflowType;
  private final WorkflowOptions options;
  private final Header header;
  private final EncodedValues args;

  private ScheduleActionStartWorkflow(
      String workflowType, WorkflowOptions options, Header header, EncodedValues args) {
    this.workflowType = workflowType;
    this.options = options;
    this.header = header;
    this.args = args;
  }

  public static class Builder {

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
    public Builder setArgs(Optional<Payloads> payloads, DataConverter converter) {
      this.args = new EncodedValues(payloads, converter);
      return this;
    }

    /** Set the workflow arguments to use when starting a workflow action. */
    public Builder setArgs(Object... args) {
      this.args = new EncodedValues(args);
      return this;
    }

    private String workflowType;
    private WorkflowOptions options;
    private Header header;
    private EncodedValues args;

    private Builder() {}

    private Builder(ScheduleActionStartWorkflow options) {
      if (options == null) {
        return;
      }
      this.workflowType = options.workflowType;
      this.options = options.options;
      this.header = options.header;
      this.args = options.args;
    }

    public ScheduleActionStartWorkflow build() {
      return new ScheduleActionStartWorkflow(
          workflowType, options, header == null ? Header.empty() : header, args);
    }
  }
}
