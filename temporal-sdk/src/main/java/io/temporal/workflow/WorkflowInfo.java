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
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Provides information about the current Workflow Execution and Run. Also provides access to
 * immutable information about connected entities like Parent Workflow Execution or a previous Run.
 */
public interface WorkflowInfo {

  /**
   * @return Workflow Namespace
   */
  String getNamespace();

  /**
   * @return Workflow ID
   */
  String getWorkflowId();

  /**
   * @return Workflow Type
   */
  String getWorkflowType();

  /**
   * Note: RunId is unique identifier of one workflow code execution. Reset changes RunId.
   *
   * @return Workflow Run ID that is handled by the current workflow code execution.
   * @see #getOriginalExecutionRunId() for RunId variation that is resistant to Resets
   * @see #getFirstExecutionRunId() for the very first RunId that is preserved along the whole
   *     Workflow Execution chain, including ContinueAsNew, Retry, Cron and Reset.
   */
  @Nonnull
  String getRunId();

  /**
   * @return The very first original RunId of the current Workflow Execution preserved along the
   *     chain of ContinueAsNew, Retry, Cron and Reset. Identifies the whole Runs chain of Workflow
   *     Execution.
   */
  @Nonnull
  String getFirstExecutionRunId();

  /**
   * @return Run ID of the previous Workflow Run which continued-as-new or retried or cron-scheduled
   *     into the current Workflow Run.
   */
  Optional<String> getContinuedExecutionRunId();

  /**
   * Note: This value is NOT preserved by continue-as-new, retries or cron Runs. They are separate
   * Runs of one Workflow Execution Chain.
   *
   * @return original RunId of the current Workflow Run. This value is preserved during Reset which
   *     changes RunID.
   * @see #getFirstExecutionRunId() for the very first RunId that is preserved along the whole
   *     Workflow Execution chain, including ContinueAsNew, Retry, Cron and Reset.
   */
  @Nonnull
  String getOriginalExecutionRunId();

  /**
   * @return Workflow Task Queue name
   */
  String getTaskQueue();

  /**
   * @return Timeout for a Workflow Run specified during Workflow start in {@link
   *     io.temporal.client.WorkflowOptions.Builder#setWorkflowRunTimeout(Duration)}
   */
  Duration getWorkflowRunTimeout();

  /**
   * @return Timeout for the Workflow Execution specified during Workflow start in {@link
   *     io.temporal.client.WorkflowOptions.Builder#setWorkflowExecutionTimeout(Duration)}
   */
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
   * @deprecated use {@link Workflow#getTypedSearchAttributes()} instead.
   */
  @Deprecated
  @Nullable
  SearchAttributes getSearchAttributes();

  /**
   * @return Workflow ID of the parent Workflow
   */
  Optional<String> getParentWorkflowId();

  /**
   * @return Run ID of the parent Workflow
   */
  Optional<String> getParentRunId();

  /**
   * @return Workflow retry attempt handled by this Workflow code execution. Starts on "1".
   */
  int getAttempt();

  /**
   * @return Workflow cron schedule
   */
  String getCronSchedule();

  /**
   * @return length of Workflow history up until the current moment of execution. This value changes
   *     during the lifetime of a Workflow Execution. You may use this information to decide when to
   *     call {@link Workflow#continueAsNew(Object...)}.
   */
  long getHistoryLength();

  /**
   * @return size of Workflow history in bytes up until the current moment of execution. This value
   *     changes during the lifetime of a Workflow Execution.
   */
  long getHistorySize();

  /**
   * @return true if the server is configured to suggest continue as new and it is suggested. This
   *     value changes during the lifetime of a Workflow Execution.
   */
  boolean isContinueAsNewSuggested();
}
