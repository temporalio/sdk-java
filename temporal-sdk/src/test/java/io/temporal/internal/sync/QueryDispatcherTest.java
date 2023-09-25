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

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.temporal.api.common.v1.Payloads;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.interceptors.Header;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import java.lang.reflect.Type;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

public class QueryDispatcherTest {

  private QueryDispatcher dispatcher;

  @Before
  public void init() {
    initializeDispatcher("QueryA", "QueryB");
  }

  private QueryDispatcher initializeDispatcher(String... queries) {
    // Initialize the dispatcher.
    dispatcher = new QueryDispatcher(DefaultDataConverter.STANDARD_INSTANCE);
    for (String query : queries) {
      WorkflowOutboundCallsInterceptor.RegisterQueryInput request =
          new WorkflowOutboundCallsInterceptor.RegisterQueryInput(
              query, new Class[] {}, new Type[] {}, null);
      dispatcher.registerQueryHandlers(request);
    }
    return dispatcher;
  }

  @Test
  public void testQuerySuccess() {
    // Set up a mock interceptor.
    WorkflowInboundCallsInterceptor.QueryOutput queryOutput =
        new WorkflowInboundCallsInterceptor.QueryOutput("dummy");
    WorkflowInboundCallsInterceptor mockInterceptor = mock(WorkflowInboundCallsInterceptor.class);
    when(mockInterceptor.handleQuery(any())).thenReturn(queryOutput);

    // Initialize the dispatcher.
    dispatcher.setInboundCallsInterceptor(mockInterceptor);

    // Invoke functionality under test, expect no exceptions for an existing query.
    Optional<Payloads> queryResult =
        dispatcher.handleQuery("QueryB", Header.empty(), Optional.empty());
    assertTrue(queryResult.isPresent());
  }

  @Test
  public void testQueryDispatcherException() {
    // Invoke functionality under test, expect an exception with the correct output for a
    // non-existing query.
    Throwable exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> {
              dispatcher.handleQuery("QueryC", Header.empty(), null);
            });
    assertEquals("Unknown query type: QueryC, knownTypes=[QueryA, QueryB]", exception.getMessage());
  }
}
