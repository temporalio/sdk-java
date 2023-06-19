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
package io.temporal.internal.async

import io.temporal.api.common.v1.SearchAttributes
import io.temporal.internal.replay.ReplayWorkflowContext
import io.temporal.workflow.KotlinWorkflowInfo
import java.time.Duration

internal class KotlinWorkflowInfoImpl(
  private val context: ReplayWorkflowContext
) : KotlinWorkflowInfo {
  override val namespace: String = context.namespace
  override val workflowId: String = context.workflowId
  override val workflowType: String = context.workflowType.name
  override val runId: String = context.runId
  override val firstExecutionRunId: String = context.firstExecutionRunId
  override val continuedExecutionRunId: String? = context.continuedExecutionRunId.orElseGet(null)
  override val originalExecutionRunId: String = context.originalExecutionRunId
  override val taskQueue: String = context.taskQueue
  override val workflowRunTimeout: Duration? = context.workflowRunTimeout
  override val workflowExecutionTimeout: Duration? = context.workflowExecutionTimeout
  override val runStartedTimestampMillis: Long = context.runStartedTimestampMillis
  override val searchAttributes: SearchAttributes? = context.searchAttributes
  override val parentWorkflowId: String? =
    if (context.parentWorkflowExecution != null) context.parentWorkflowExecution.workflowId else null
  override val parentRunId: String? =
    if (context.parentWorkflowExecution != null) context.parentWorkflowExecution.runId else null
  override val attempt: Int = context.attempt
  override val cronSchedule: String? = context.cronSchedule
  override val historyLength: Long = context.currentWorkflowTaskStartedEventId

  override fun toString(): String {
    return "KotlinWorkflowInfoImpl(context=$context, namespace='$namespace', workflowId='$workflowId', " +
      "workflowType='$workflowType', runId='$runId', firstExecutionRunId='$firstExecutionRunId', " +
      "continuedExecutionRunId=$continuedExecutionRunId, originalExecutionRunId='$originalExecutionRunId', " +
      "taskQueue='$taskQueue', workflowRunTimeout=$workflowRunTimeout, " +
      "workflowExecutionTimeout=$workflowExecutionTimeout, runStartedTimestampMillis=$runStartedTimestampMillis, " +
      "searchAttributes=$searchAttributes, parentWorkflowId=$parentWorkflowId, parentRunId=$parentRunId, " +
      "attempt=$attempt, cronSchedule=$cronSchedule, historyLength=$historyLength)"
  }
}
