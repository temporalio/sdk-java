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
        dispatcher.handleQuery(
            mock(SyncWorkflowContext.class), "QueryB", Header.empty(), Optional.empty());
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
              dispatcher.handleQuery(
                  mock(SyncWorkflowContext.class), "QueryC", Header.empty(), null);
            });
    assertEquals("Unknown query type: QueryC, knownTypes=[QueryA, QueryB]", exception.getMessage());
  }
}
