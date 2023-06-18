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
package io.temporal.workflow

import io.temporal.api.common.v1.SearchAttributes
import java.time.Duration
import java.util.*

/**
 * Provides information about the current Workflow Execution and Run. Also provides access to
 * immutable information about connected entities like Parent Workflow Execution or a previous Run.
 */
interface KotlinWorkflowInfo {
    /**
     * @return Workflow Namespace
     */
    val namespace: String

    /**
     * @return Workflow ID
     */
    val workflowId: String

    /**
     * @return Workflow Type
     */
    val workflowType: String

    /**
     * Note: RunId is unique identifier of one workflow code execution. Reset changes RunId.
     *
     * @return Workflow Run ID that is handled by the current workflow code execution.
     * @see .getOriginalExecutionRunId
     * @see .getFirstExecutionRunId
     */
    val runId: String

    /**
     * @return The very first original RunId of the current Workflow Execution preserved along the
     * chain of ContinueAsNew, Retry, Cron and Reset. Identifies the whole Runs chain of Workflow
     * Execution.
     */
    val firstExecutionRunId: String

    /**
     * @return Run ID of the previous Workflow Run which continued-as-new or retried or cron-scheduled
     * into the current Workflow Run.
     */
    val continuedExecutionRunId: String?

    /**
     * Note: This value is NOT preserved by continue-as-new, retries or cron Runs. They are separate
     * Runs of one Workflow Execution Chain.
     *
     * @return original RunId of the current Workflow Run. This value is preserved during Reset which
     * changes RunID.
     * @see .getFirstExecutionRunId
     */
    val originalExecutionRunId: String

    /**
     * @return Workflow Task Queue name
     */
    val taskQueue: String

    /**
     * @return Timeout for a Workflow Run specified during Workflow start in [     ][io.temporal.client.WorkflowOptions.Builder.setWorkflowRunTimeout]
     */
    val workflowRunTimeout: Duration?

    /**
     * @return Timeout for the Workflow Execution specified during Workflow start in [     ][io.temporal.client.WorkflowOptions.Builder.setWorkflowExecutionTimeout]
     */
    val workflowExecutionTimeout: Duration?

    /**
     * The time workflow run has started. Note that this time can be different from the time workflow
     * function started actual execution.
     */
    val runStartedTimestampMillis: Long

    /**
     * This method is used to get raw proto serialized Search Attributes.
     *
     *
     * Consider using more user-friendly methods on [Workflow] class, including [ ][Workflow.getSearchAttributes], [Workflow.getSearchAttribute] or [ ][Workflow.getSearchAttributeValues] instead of this method to access deserialized search
     * attributes.
     *
     * @return raw Search Attributes Protobuf entity, null if empty
     */
    @get:Deprecated("use {@link Workflow#getTypedSearchAttributes()} instead.")
    val searchAttributes: SearchAttributes?

    /**
     * @return Workflow ID of the parent Workflow
     */
    val parentWorkflowId: String?

    /**
     * @return Run ID of the parent Workflow
     */
    val parentRunId: String?

    /**
     * @return Workflow retry attempt handled by this Workflow code execution. Starts on "1".
     */
    val attempt: Int

    /**
     * @return Workflow cron schedule
     */
    val cronSchedule: String?

    /**
     * @return length of Workflow history up until the current moment of execution. This value changes
     * during the lifetime of a Workflow Execution. You may use this information to decide when to
     * call [Workflow.continueAsNew].
     */
    val historyLength: Long
}