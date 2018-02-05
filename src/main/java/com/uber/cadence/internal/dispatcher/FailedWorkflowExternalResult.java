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
package com.uber.cadence.internal.dispatcher;

import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.client.WorkflowExternalResult;

import java.util.concurrent.TimeUnit;

final class FailedWorkflowExternalResult<R> implements WorkflowExternalResult<R> {
    private final Exception failure;

    public FailedWorkflowExternalResult(Exception failure) {
        this.failure = failure;
    }

    @Override
    public WorkflowExecution getExecution() {
        throw new IllegalStateException("StartWorkflowExecution failed: ", failure);
    }

    @Override
    public <G> void signal(String name, G input) {
        throw new IllegalStateException("Failed");
    }

    @Override
    public R getResult() {
        return getResult(0, TimeUnit.SECONDS);
    }

    @Override
    public R getResult(long timeout, TimeUnit unit) {
        if (failure != null) {
            if (failure instanceof RuntimeException) {
                throw (RuntimeException) failure;
            } else {
                throw new RuntimeException(failure);
            }
        }
        throw new RuntimeException("Unreachable");
    }
}
