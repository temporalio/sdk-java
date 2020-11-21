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

package io.temporal.internal.context;

import io.temporal.api.common.v1.Header;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.context.ContextPropagator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Common methods for dealing with context */
public class ContextPropagatorUtils {

  public static Map<String, Object> extractContextsFromHeaders(
      List<ContextPropagator> contextPropagators, Header headers) {

    if (contextPropagators == null || contextPropagators.isEmpty()) {
      return new HashMap<>();
    }

    if (headers == null) {
      return new HashMap<>();
    }

    Map<String, Payload> headerData = new HashMap<>();
    for (Map.Entry<String, Payload> pair : headers.getFieldsMap().entrySet()) {
      headerData.put(pair.getKey(), pair.getValue());
    }

    Map<String, Object> contextData = new HashMap<>();
    for (ContextPropagator propagator : contextPropagators) {

      // Only send the context propagator the fields that belong to them
      // Change the map from MyPropagator:foo -> bar to foo -> bar
      Map<String, Payload> filteredData =
          headerData.entrySet().stream()
              .filter(e -> e.getKey().startsWith(propagator.getName()))
              .collect(
                  Collectors.toMap(
                      e -> e.getKey().substring(propagator.getName().length() + 1),
                      Map.Entry::getValue));
      contextData.put(propagator.getName(), propagator.deserializeContext(filteredData));
    }

    return contextData;
  }

  public static Map<String, Payload> extractContextsAndConvertToBytes(
      List<ContextPropagator> contextPropagators) {
    if (contextPropagators == null) {
      return null;
    }
    Map<String, Payload> result = new HashMap<>();
    for (ContextPropagator propagator : contextPropagators) {
      // Get the serialized context from the propagator
      Map<String, Payload> serializedContext =
          propagator.serializeContext(propagator.getCurrentContext());
      // Namespace each entry in case of overlaps, so foo -> bar becomes MyPropagator:foo -> bar
      Map<String, Payload> namespacedSerializedContext =
          serializedContext.entrySet().stream()
              .collect(
                  Collectors.toMap(
                      e -> propagator.getName() + ":" + e.getKey(), Map.Entry::getValue));
      result.putAll(namespacedSerializedContext);
    }
    return result;
  }
}
