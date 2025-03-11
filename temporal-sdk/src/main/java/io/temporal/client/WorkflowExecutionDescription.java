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

package io.temporal.client;

import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.common.Experimental;
import io.temporal.common.converter.DataConverter;
import io.temporal.payload.context.WorkflowSerializationContext;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Contains information about a workflow execution. */
public class WorkflowExecutionDescription extends WorkflowExecutionMetadata {
  private final @Nonnull DataConverter dataConverter;
  private final @Nonnull DescribeWorkflowExecutionResponse response;

  public WorkflowExecutionDescription(
      @Nonnull DescribeWorkflowExecutionResponse response, @Nonnull DataConverter dataConverter) {
    super(response.getWorkflowExecutionInfo(), dataConverter);
    this.dataConverter = dataConverter;
    this.response = response;
  }

  /**
   * Get the fixed summary for this workflow execution.
   *
   * @apiNote Will be decoded on each invocation, so it is recommended to cache the result if it is
   *     used multiple times.
   */
  @Experimental
  @Nullable
  public String getStaticSummary() {
    if (!response.getExecutionConfig().getUserMetadata().hasSummary()) {
      return null;
    }
    return dataConverter
        .withContext(
            new WorkflowSerializationContext(
                response.getWorkflowExecutionInfo().getParentNamespaceId(),
                response.getWorkflowExecutionInfo().getExecution().getWorkflowId()))
        .fromPayload(
            response.getExecutionConfig().getUserMetadata().getSummary(),
            String.class,
            String.class);
  }

  /**
   * Get the details summary for this workflow execution.
   *
   * @apiNote Will be decoded on each invocation, so it is recommended to cache the result if it is
   *     used multiple times.
   */
  @Experimental
  @Nullable
  public String getStaticDetails() {
    if (!response.getExecutionConfig().getUserMetadata().hasDetails()) {
      return null;
    }
    return dataConverter
        .withContext(
            new WorkflowSerializationContext(
                response.getWorkflowExecutionInfo().getParentNamespaceId(),
                response.getWorkflowExecutionInfo().getExecution().getWorkflowId()))
        .fromPayload(
            response.getExecutionConfig().getUserMetadata().getDetails(),
            String.class,
            String.class);
  }

  /** Returns the raw response from the Temporal service. */
  public DescribeWorkflowExecutionResponse getRawDescription() {
    return response;
  }
}
