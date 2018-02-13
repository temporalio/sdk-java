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
package com.uber.cadence.internal.generic;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

public interface GenericAsyncActivityClient {

    /**
     * Used to dynamically schedule an activity for execution
     * 
     * @param parameters
     *            An object which encapsulates all the information required to
     *            schedule an activity for execution
     * @param callback Callback that is called upon activity completion or failure.
     * @return cancellation handle. Invoke {@link Consumer#accept(Object)} to cancel activity task.
     */
    Consumer<Throwable> scheduleActivityTask(ExecuteActivityParameters parameters,
                                             BiConsumer<byte[], RuntimeException> callback);

    /**
     * Used to dynamically schedule an activity for execution
     * 
     * @param activity
     *            Name of activity
     * @param input
     *            A map of all input parameters to that activity
     * @param callback Callback that is called upon activity completion or failure.
     * @return cancellation handle. Invoke {@link Consumer#accept(Object)} to cancel activity task.
     */
//    Consumer<Throwable> scheduleActivityTask(String activity, byte[] input, BiConsumer<byte[], Throwable> callback);

}
