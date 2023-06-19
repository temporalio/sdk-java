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
package io.temporal.kotlin.interceptors

import io.temporal.common.Experimental
import io.temporal.common.interceptors.Header

/**
 * Intercepts inbound calls to the workflow execution on the worker side.
 *
 *
 * An instance should be created in [ ][KotlinWorkerInterceptor.interceptWorkflow].
 *
 *
 * The calls to this interceptor are executed under workflow context, all the rules and
 * restrictions on the workflow code apply. See [io.temporal.workflow].
 *
 *
 * Prefer extending [WorkflowInboundCallsInterceptorBase] and overriding only the methods
 * you need instead of implementing this interface directly. [ ] provides correct default implementations to all the methods
 * of this interface.
 *
 *
 * The implementation must forward all the calls to `next`, but it may change the input
 * parameters.
 *
 * @see KotlinWorkerInterceptor.interceptWorkflow
 */
@Experimental
interface WorkflowInboundCallsInterceptor {
    class WorkflowInput(val header: Header?, val arguments: Array<Any>)
    class WorkflowOutput(val result: Any?)
    class SignalInput(val signalName: String, val arguments: Array<Any>, val eventId: Long)
    class QueryInput(val queryName: String, val arguments: Array<Any>)
    class QueryOutput(val result: Any)

    @Experimental
    class UpdateInput(val updateName: String, val arguments: Array<Any>)

    @Experimental
    class UpdateOutput(val result: Any)

    /**
     * Called when workflow class is instantiated. May create a [ ] instance. The instance must forward all the calls to `outboundCalls`, but it may change the input parameters.
     *
     *
     * The instance should be passed into the {next.init(newWorkflowOutboundCallsInterceptor)}.
     *
     * @param outboundCalls an existing interceptor instance to be proxied by the interceptor created
     * inside this method
     * @see KotlinWorkerInterceptor.interceptWorkflow for the definition of "next" {@link
     * *     WorkflowInboundCallsInterceptor}
     */
    suspend fun init(outboundCalls: WorkflowOutboundCallsInterceptor)

    /**
     * Called when workflow main method is called.
     *
     * @return result of the workflow execution.
     */
    suspend fun execute(input: WorkflowInput): WorkflowOutput

    /** Called when signal is delivered to a workflow execution.  */
    suspend fun handleSignal(input: SignalInput)

    /** Called when a workflow is queried.  */
    fun handleQuery(input: QueryInput): QueryOutput

    /**
     * Called when update workflow execution request is delivered to a workflow execution, before the
     * update is executed.
     */
    @Experimental
    fun validateUpdate(input: UpdateInput)

    /**
     * Called when update workflow execution request is delivered to a workflow execution, after
     * passing the validator.
     */
    @Experimental
    suspend fun executeUpdate(input: UpdateInput): UpdateOutput

}