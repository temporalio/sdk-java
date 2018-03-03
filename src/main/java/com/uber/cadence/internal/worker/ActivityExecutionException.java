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
package com.uber.cadence.internal.worker;

import com.uber.cadence.PollForActivityTaskResponse;
import com.uber.cadence.WorkflowService;

/**
 * This exception is expected to be thrown from
 * {@link ActivityImplementation#execute(WorkflowService.Iface, String, PollForActivityTaskResponse)} )}
 * as it contains details property in the format that the activity client code
 * in the decider understands.
 * <p>
 * It is not expected to be thrown or caught by the application level code.
 *
 * @author fateev
 */
@SuppressWarnings("serial")
public final class ActivityExecutionException extends RuntimeException {

    private final byte[] details;

    private final String reason;

    /**
     * Construct exception with given arguments.
     *
     * @param message
     * @param reason  value of reason field
     * @param details application specific failure details
     */
    public ActivityExecutionException(String message, String reason, byte[] details, Throwable cause) {
        super(message, cause, false, false);
        this.reason = reason;
        this.details = details;
    }

    public byte[] getDetails() {
        return details;
    }

    public String getReason() {
        return reason;
    }
}
