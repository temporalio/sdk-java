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

package io.temporal.internal.sync;

import io.temporal.api.common.v1.Payloads;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.EncodedValues;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.workflow.DynamicQueryHandler;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class QueryDispatcher {

  private static final Logger log = LoggerFactory.getLogger(QueryDispatcher.class);

  private WorkflowInboundCallsInterceptor inboundCallsInterceptor;
  private final DataConverter converter;

  private final Map<String, WorkflowOutboundCallsInterceptor.RegisterQueryInput> queryCallbacks =
      new HashMap<>();

  private DynamicQueryHandler dynamicQueryHandler;

  public QueryDispatcher(DataConverter converter) {
    this.converter = converter;
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

  public Optional<Payloads> handleQuery(String queryName, Optional<Payloads> input) {
    WorkflowOutboundCallsInterceptor.RegisterQueryInput handler = queryCallbacks.get(queryName);
    Object[] args;
    if (handler == null) {
      if (dynamicQueryHandler == null) {
        throw new IllegalArgumentException(
            "Unknown query type: " + queryName + ", knownTypes=" + queryCallbacks.keySet());
      }
      args = new Object[] {new EncodedValues(input, converter)};
    } else {
      args =
          DataConverter.arrayFromPayloads(
              converter, input, handler.getArgTypes(), handler.getGenericArgTypes());
    }
    Object result =
        inboundCallsInterceptor
            .handleQuery(new WorkflowInboundCallsInterceptor.QueryInput(queryName, args))
            .getResult();
    return converter.toPayloads(result);
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
}
