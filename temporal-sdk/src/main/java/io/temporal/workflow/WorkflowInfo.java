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

import io.temporal.api.common.v1.SearchAttributes;
import java.time.Duration;
import java.util.Optional;
import javax.annotation.Nullable;

public interface WorkflowInfo {

  String getNamespace();

  String getWorkflowId();

  String getRunId();

  String getWorkflowType();

  Optional<String> getContinuedExecutionRunId();

  String getTaskQueue();

  Duration getWorkflowRunTimeout();

  Duration getWorkflowExecutionTimeout();

  /**
   * The time workflow run has started. Note that this time can be different from the time workflow
   * function started actual execution.
   */
  long getRunStartedTimestampMillis();

  /**
   * This method is used to get raw proto serialized Search Attributes.
   *
   * <p>Consider using more user-friendly methods on {@link Workflow} class, including {@link
   * Workflow#getSearchAttributes()}, {@link Workflow#getSearchAttribute(String)} or {@link
   * Workflow#getSearchAttributeValues(String)} instead of this method to access deserialized search
   * attributes.
   *
   * @return raw Search Attributes Protobuf entity, null if empty
   */
  @Nullable
  SearchAttributes getSearchAttributes();

  Optional<String> getParentWorkflowId();

  Optional<String> getParentRunId();

  int getAttempt();

  String getCronSchedule();
}
