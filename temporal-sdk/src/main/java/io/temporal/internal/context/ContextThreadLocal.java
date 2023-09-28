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

package io.temporal.internal.context;

import io.temporal.common.context.ContextPropagator;
import io.temporal.workflow.WorkflowThreadLocal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** This class holds the current set of context propagators */
public class ContextThreadLocal {

  private static final WorkflowThreadLocal<List<ContextPropagator>> contextPropagators =
      WorkflowThreadLocal.withCachedInitial(
          new Supplier<List<ContextPropagator>>() {
            @Override
            public List<ContextPropagator> get() {
              return new ArrayList<>();
            }
          });

  /** Sets the list of context propagators for the thread */
  public static void setContextPropagators(List<ContextPropagator> propagators) {
    if (propagators == null || propagators.isEmpty()) {
      return;
    }
    contextPropagators.set(propagators);
  }

  public static List<ContextPropagator> getContextPropagators() {
    return contextPropagators.get();
  }

  public static Map<String, Object> getCurrentContextForPropagation() {
    Map<String, Object> contextData = new HashMap<>();
    for (ContextPropagator propagator : contextPropagators.get()) {
      contextData.put(propagator.getName(), propagator.getCurrentContext());
    }
    return contextData;
  }

  public static void propagateContextToCurrentThread(Map<String, Object> contextData) {
    if (contextData == null || contextData.isEmpty()) {
      return;
    }
    for (ContextPropagator propagator : contextPropagators.get()) {
      if (contextData.containsKey(propagator.getName())) {
        propagator.setCurrentContext(contextData.get(propagator.getName()));
      }
    }
  }
}
