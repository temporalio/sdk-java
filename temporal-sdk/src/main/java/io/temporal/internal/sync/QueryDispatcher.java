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

package io.temporal.internal.sync;

import io.temporal.api.common.v1.Payloads;
import io.temporal.api.sdk.v1.WorkflowInteractionDefinition;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.EncodedValues;
import io.temporal.common.interceptors.Header;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.workflow.DynamicQueryHandler;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class QueryDispatcher {
  private static final Logger log = LoggerFactory.getLogger(QueryDispatcher.class);

  private final DataConverter dataConverterWithWorkflowContext;
  private final Map<String, WorkflowOutboundCallsInterceptor.RegisterQueryInput> queryCallbacks =
      new HashMap<>();

  private DynamicQueryHandler dynamicQueryHandler;
  private WorkflowInboundCallsInterceptor inboundCallsInterceptor;

  public QueryDispatcher(DataConverter dataConverterWithWorkflowContext) {
    this.dataConverterWithWorkflowContext = dataConverterWithWorkflowContext;
  }

  public void setInboundCallsInterceptor(WorkflowInboundCallsInterceptor inboundCallsInterceptor) {
    this.inboundCallsInterceptor = inboundCallsInterceptor;
  }

  /** Called from the interceptor tail */
  public WorkflowInboundCallsInterceptor.QueryOutput handleInterceptedQuery(
      WorkflowInboundCallsInterceptor.QueryInput input) {
    String queryName = input.getQueryName();
    Object[] args = input.getArguments();
    WorkflowOutboundCallsInterceptor.RegisterQueryInput handler = queryCallbacks.get(queryName);
    Object result;
    if (handler == null) {
      if (dynamicQueryHandler != null) {
        result = dynamicQueryHandler.handle(queryName, (EncodedValues) args[0]);
      } else {
        throw new IllegalStateException("Unknown query type: " + queryName);
      }
    } else {
      result = handler.getCallback().apply(args);
    }
    return new WorkflowInboundCallsInterceptor.QueryOutput(result);
  }

  public Optional<Payloads> handleQuery(String queryName, Header header, Optional<Payloads> input) {
    WorkflowOutboundCallsInterceptor.RegisterQueryInput handler = queryCallbacks.get(queryName);
    Object[] args;
    if (handler == null) {
      if (dynamicQueryHandler == null) {
        throw new IllegalArgumentException(
            "Unknown query type: " + queryName + ", knownTypes=" + queryCallbacks.keySet());
      }
      args = new Object[] {new EncodedValues(input, dataConverterWithWorkflowContext)};
    } else {
      args =
          dataConverterWithWorkflowContext.fromPayloads(
              input, handler.getArgTypes(), handler.getGenericArgTypes());
    }
    Object result =
        inboundCallsInterceptor
            .handleQuery(new WorkflowInboundCallsInterceptor.QueryInput(queryName, header, args))
            .getResult();
    return dataConverterWithWorkflowContext.toPayloads(result);
  }

  public void registerQueryHandlers(WorkflowOutboundCallsInterceptor.RegisterQueryInput request) {
    String queryType = request.getQueryType();
    if (queryCallbacks.containsKey(queryType)) {
      throw new IllegalStateException("Query \"" + queryType + "\" is already registered");
    }
    queryCallbacks.put(queryType, request);
  }

  public void registerDynamicQueryHandler(
      WorkflowOutboundCallsInterceptor.RegisterDynamicQueryHandlerInput input) {
    dynamicQueryHandler = input.getHandler();
  }

  public List<WorkflowInteractionDefinition> getQueryHandlers() {
    List<WorkflowInteractionDefinition> handlers = new ArrayList<>(queryCallbacks.size() + 1);
    for (Map.Entry<String, WorkflowOutboundCallsInterceptor.RegisterQueryInput> entry :
        queryCallbacks.entrySet()) {
      WorkflowOutboundCallsInterceptor.RegisterQueryInput handler = entry.getValue();
      handlers.add(
          WorkflowInteractionDefinition.newBuilder()
              .setName(handler.getQueryType())
              .setDescription(handler.getDescription())
              .build());
    }
    if (dynamicQueryHandler != null) {
      handlers.add(
          WorkflowInteractionDefinition.newBuilder()
              .setDescription(dynamicQueryHandler.getDescription())
              .build());
    }
    handlers.sort(Comparator.comparing(WorkflowInteractionDefinition::getName));
    return handlers;
  }
}
