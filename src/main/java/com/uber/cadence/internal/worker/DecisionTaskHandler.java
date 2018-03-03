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

import java.util.Iterator;

/**
 * Base class for workflow task handlers.
 *
 * @author fateev, suskin
 */
public interface DecisionTaskHandler {

    /**
     * The implementation should be called when a polling SWF Decider receives a
     * new WorkflowTask.
     *
     * @param decisionTaskIterator The decision task to handle. Iterator wraps the task to support
     *                             pagination of the history. The events are loaded lazily when history iterator next is called.
     *                             It is expected that the method implementation aborts decision by rethrowing any
     *                             exception from {@link Iterator#next()}.
     * @return One of the possible decision task replies: RespondDecisionTaskCompletedRequest,
     * RespondQueryTaskCompletedRequest, RespondDecisionTaskFailedRequest
     * @throws Exception if processing task wasn't possible which would lead to decision failure.
     */
    Object handleDecisionTask(DecisionTaskWithHistoryIterator decisionTaskIterator) throws Exception;
}
