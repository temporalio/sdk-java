/*
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
package com.uber.cadence.generic;

import com.uber.cadence.WorkflowExecution;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

public interface GenericAsyncWorkflowClient {

    /**
     * Start child workflow.
     *
     * @param parameters An object which encapsulates all the information required to
     *                   schedule a child workflow for execution
     * @param callback   Callback that is called upon child workflow completion or failure.
     * @return cancellation handle. Invoke {@link Consumer#accept(Object)} to cancel activity task.
     */
    Consumer<Throwable> startChildWorkflow(StartChildWorkflowExecutionParameters parameters, Consumer<String> runIdCallback,
                                           BiConsumer<byte[], Throwable> callback);

    Consumer<Throwable> startChildWorkflow(String workflow, byte[] input, Consumer<String> runIdCallback, BiConsumer<byte[], Throwable> callback);

    Consumer<Throwable> startChildWorkflow(String workflow, byte[] input, BiConsumer<byte[], Throwable> callback);

// TODO(Cadence):   Promise<Void> signalWorkflowExecution(SignalExternalWorkflowParameters signalParameters);

    void requestCancelWorkflowExecution(WorkflowExecution execution);

    void continueAsNewOnCompletion(ContinueAsNewWorkflowExecutionParameters parameters);

    /**
     * Deterministic unique Id generator
     */
    String generateUniqueId();

}
