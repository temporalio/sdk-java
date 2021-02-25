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

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.temporal.api.common.v1.Payloads;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import java.lang.reflect.Type;
import java.util.Optional;
import org.junit.Test;

public class QueryDispatcherTest {

  private QueryDispatcher initializeDispatcher() {
    // Initialize the dispatcher.
    QueryDispatcher dispatcher = new QueryDispatcher(DataConverter.getDefaultInstance());
    WorkflowOutboundCallsInterceptor.RegisterQueryInput requestA =
        new WorkflowOutboundCallsInterceptor.RegisterQueryInput(
            "QueryA", new Class[] {}, new Type[] {}, null);
    WorkflowOutboundCallsInterceptor.RegisterQueryInput requestB =
        new WorkflowOutboundCallsInterceptor.RegisterQueryInput(
            "QueryB", new Class[] {}, new Type[] {}, null);
    dispatcher.registerQueryHandlers(requestA);
    dispatcher.registerQueryHandlers(requestB);

    return dispatcher;
  }

  @Test
  public void testQuerySuccess() {
    QueryDispatcher dispatcher = initializeDispatcher();

    // Set up a mock interceptor.
    WorkflowInboundCallsInterceptor.QueryOutput queryOutput =
        new WorkflowInboundCallsInterceptor.QueryOutput("dummy");
    WorkflowInboundCallsInterceptor mockInterceptor = mock(WorkflowInboundCallsInterceptor.class);
    when(mockInterceptor.handleQuery(any())).thenReturn(queryOutput);

    // Initialize the dispatcher.
    dispatcher.setInboundCallsInterceptor(mockInterceptor);

    // Invoke functionality under test, expect no exceptions for an existing query.
    Optional<Payloads> queryResult = dispatcher.handleQuery("QueryB", Optional.empty());
    assertTrue(queryResult.isPresent());
  }

  @Test
  public void testQueryDispatcherException() {
    QueryDispatcher dispatcher = initializeDispatcher();
    // Invoke functionality under test, expect an exception with the correct output for a
    // non-existing query.
    Throwable exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> {
              dispatcher.handleQuery("QueryC", null);
            });
    assertEquals("Unknown query type: QueryC, knownTypes=[QueryA, QueryB]", exception.getMessage());
  }
}
