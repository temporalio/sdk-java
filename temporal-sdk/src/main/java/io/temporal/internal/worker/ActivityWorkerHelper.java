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

package io.temporal.internal.worker;

import io.temporal.api.common.v1.Payload;
import io.temporal.common.context.ContextPropagator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Provides functionality common for all types of ActivityWorkers ({@code
 * io.temporal.internal.ActivityWorker} and {@code io.temporal.internal.LocalActivityWorker})
 */
class ActivityWorkerHelper {
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
