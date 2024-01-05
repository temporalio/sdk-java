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

import io.temporal.api.common.v1.Payloads
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes
import io.temporal.common.interceptors.Header
import io.temporal.failure.CanceledFailure
import io.temporal.failure.TemporalFailure
import io.temporal.internal.sync.DestroyWorkflowThreadError
import io.temporal.internal.sync.WorkflowInternal
import io.temporal.internal.worker.WorkflowExecutionException
import io.temporal.serviceclient.CheckedExceptionWrapper
import io.temporal.worker.WorkflowImplementationOptions
import io.temporal.workflow.Workflow
import org.slf4j.LoggerFactory
import java.util.*

internal class KotlinWorkflowExecutionHandler(
  private val context: KotlinWorkflowContext,
  private val workflow: KotlinWorkflowDefinition,
  private val attributes: WorkflowExecutionStartedEventAttributes,
  private val implementationOptions: WorkflowImplementationOptions
) {
  var output: Payloads? = null
  var isDone = false

  suspend fun runWorkflowMethod() {
    try {
      val input = if (attributes.hasInput()) attributes.input else null
      output = workflow.execute(Header(attributes.header), input)
    } catch (e: Throwable) {
      applyWorkflowFailurePolicyAndRethrow(e)
    } finally {
      isDone = true
    }
  }

//  fun cancel(reason: String?) {}
//  fun close() {}

  suspend fun handleSignal(signalName: String?, input: Optional<Payloads?>?, eventId: Long) {
    try {
      TODO("Not yet implemented")
//      context.handleSignal(signalName, input, eventId)
    } catch (e: Throwable) {
      applyWorkflowFailurePolicyAndRethrow(e)
    }
  }

  //
//  fun handleQuery(type: String?, args: Optional<Payloads?>?): Optional<Payloads> {
//    return context.handleQuery(type, args)
//  }
//
//  fun handleValidateUpdate(updateName: String?, input: Optional<Payloads?>?, eventId: Long) {
//    try {
//      context.handleValidateUpdate(updateName, input, eventId)
//    } catch (e: Throwable) {
//      applyWorkflowFailurePolicyAndRethrow(e)
//    }
//  }
//
//  fun handleExecuteUpdate(
//    updateName: String?, input: Optional<Payloads?>?, eventId: Long
//  ): Optional<Payloads> {
//    try {
//      return context.handleExecuteUpdate(updateName, input, eventId)
//    } catch (e: Throwable) {
//      applyWorkflowFailurePolicyAndRethrow(e)
//    }
//    return Optional.empty()
//  }
//
  private fun applyWorkflowFailurePolicyAndRethrow(e: Throwable) {
    if (e is DestroyWorkflowThreadError) {
      throw e
    }
    val exception = WorkflowInternal.unwrap(e)
    val failTypes = implementationOptions.failWorkflowExceptionTypes
    (exception as? TemporalFailure)?.let { throwAndFailWorkflowExecution(it) }
    for (failType in failTypes) {
      if (failType.isAssignableFrom(exception.javaClass)) {
        throwAndFailWorkflowExecution(exception)
      }
    }
    throw CheckedExceptionWrapper.wrap(exception)
  }

  private fun throwAndFailWorkflowExecution(exception: Throwable) {
    val replayWorkflowContext = context.getReplayContext()
    val fullReplayDirectQueryName = replayWorkflowContext!!.fullReplayDirectQueryName
    val info = Workflow.getInfo()
    if (fullReplayDirectQueryName != null) {
      if (log.isDebugEnabled &&
        !requestedCancellation(replayWorkflowContext.isCancelRequested, exception)
      ) {
        log.debug(
          "Replayed workflow execution failure WorkflowId='{}', RunId={}, WorkflowType='{}' for direct query QueryType='{}'",
          info.workflowId,
          info.runId,
          info.workflowType,
          fullReplayDirectQueryName,
          exception
        )
      }
    } else {
      if (log.isWarnEnabled &&
        !requestedCancellation(replayWorkflowContext.isCancelRequested, exception)
      ) {
        log.warn(
          "Workflow execution failure WorkflowId='{}', RunId={}, WorkflowType='{}'",
          info.workflowId,
          info.runId,
          info.workflowType,
          exception
        )
      }
    }
    throw WorkflowExecutionException(context.mapWorkflowExceptionToFailure(exception))
  }

  /**
   * @return true if both workflow cancellation is requested and the exception contains a
   * cancellation exception in the chain
   */
  private fun requestedCancellation(cancelRequested: Boolean, exception: Throwable): Boolean {
    return cancelRequested && isCanceledCause(exception)
  }

  companion object {
    private val log = LoggerFactory.getLogger(KotlinWorkflowExecutionHandler::class.java)
    private fun isCanceledCause(exception: Throwable): Boolean {
      var exception: Throwable? = exception
      while (exception != null) {
        if (exception is CanceledFailure) {
          return true
        }
        exception = exception.cause
      }
      return false
    }
  }
}
