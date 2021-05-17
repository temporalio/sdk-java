/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
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

package io.temporal.internal.worker.activity;

import io.temporal.api.common.v1.Payload;
import io.temporal.common.context.ContextPropagator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Provides functionality common for all types of ActivityWorkers ({@code
 * io.temporal.internal.ActivityWorker} and {@code io.temporal.internal.LocalActivityWorker})
 */
public class ActivityWorkerHelper {
  public static void deserializeAndPopulateContext(
      io.temporal.api.common.v1.Header header, List<ContextPropagator> contextPropagatorList) {
    if (contextPropagatorList == null || contextPropagatorList.isEmpty()) {
      return;
    }

    Map<String, Payload> headerData = new HashMap<>();
    for (Map.Entry<String, Payload> entry : header.getFieldsMap().entrySet()) {
      headerData.put(entry.getKey(), entry.getValue());
    }
    for (ContextPropagator propagator : contextPropagatorList) {
      propagator.setCurrentContext(propagator.deserializeContext(headerData));
    }
  }
}
