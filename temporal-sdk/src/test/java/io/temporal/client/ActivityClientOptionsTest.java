package io.temporal.client;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.uber.m3.tally.Scope;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.interceptors.ActivityClientInterceptor;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import java.util.Collections;
import org.junit.Test;

public class ActivityClientOptionsTest {

  @Test
  public void testDefaultIdentityIsNotNull() {
    ActivityClientOptions opts = ActivityClientOptions.newBuilder().build();
    assertNotNull(opts.getIdentity());
    assertFalse(opts.getIdentity().isEmpty());
  }

  @Test
  public void testToBuilderCopiesAllFields() {
    ActivityClientInterceptor interceptor = mock(ActivityClientInterceptor.class);
    ContextPropagator propagator = mock(ContextPropagator.class);
    DataConverter dc = mock(DataConverter.class);

    ActivityClientOptions original =
        ActivityClientOptions.newBuilder()
            .setNamespace("ns")
            .setIdentity("id")
            .setDataConverter(dc)
            .setInterceptors(Collections.singletonList(interceptor))
            .setContextPropagators(Collections.singletonList(propagator))
            .build();

    ActivityClientOptions copy = original.toBuilder().build();

    assertEquals(original.getNamespace(), copy.getNamespace());
    assertEquals(original.getIdentity(), copy.getIdentity());
    assertSame(original.getDataConverter(), copy.getDataConverter());
    assertEquals(original.getInterceptors(), copy.getInterceptors());
    assertEquals(original.getContextPropagators(), copy.getContextPropagators());
  }

  @Test
  public void testNewBuilderFromOptionsCopiesAllFields() {
    ActivityClientInterceptor interceptor = mock(ActivityClientInterceptor.class);
    DataConverter dc = mock(DataConverter.class);

    ActivityClientOptions original =
        ActivityClientOptions.newBuilder()
            .setNamespace("ns2")
            .setIdentity("id2")
            .setDataConverter(dc)
            .setInterceptors(Collections.singletonList(interceptor))
            .build();

    ActivityClientOptions copy = ActivityClientOptions.newBuilder(original).build();

    assertEquals(original.getNamespace(), copy.getNamespace());
    assertEquals(original.getIdentity(), copy.getIdentity());
    assertSame(original.getDataConverter(), copy.getDataConverter());
    assertEquals(original.getInterceptors(), copy.getInterceptors());
  }

  @Test
  public void testNewActivityCompletionClientIsNotNull() {
    WorkflowServiceStubs stubs = mock(WorkflowServiceStubs.class);
    WorkflowServiceStubsOptions stubsOptions = mock(WorkflowServiceStubsOptions.class);
    Scope scope = mock(Scope.class);
    when(stubs.getOptions()).thenReturn(stubsOptions);
    when(stubsOptions.getMetricsScope()).thenReturn(scope);
    when(scope.tagged(any())).thenReturn(scope);

    ActivityClient client = ActivityClient.newInstance(stubs);
    assertNotNull(client.newActivityCompletionClient());
  }
}
