package io.temporal.client;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.uber.m3.tally.Scope;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.GlobalDataConverter;
import io.temporal.common.interceptors.ActivityClientCallsInterceptor;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class ActivityClientOptionsTest {

  @Test
  public void testDefaultNamespaceIsDefault() {
    ActivityClientOptions opts = ActivityClientOptions.newBuilder().build();
    assertEquals("default", opts.getNamespace());
  }

  @Test
  public void testDefaultDataConverterIsGlobal() {
    ActivityClientOptions opts = ActivityClientOptions.newBuilder().build();
    assertSame(GlobalDataConverter.get(), opts.getDataConverter());
  }

  @Test
  public void testDefaultInterceptorsIsEmpty() {
    ActivityClientOptions opts = ActivityClientOptions.newBuilder().build();
    assertTrue(opts.getInterceptors().isEmpty());
  }

  @Test
  public void testDefaultContextPropagatorsIsEmpty() {
    ActivityClientOptions opts = ActivityClientOptions.newBuilder().build();
    assertTrue(opts.getContextPropagators().isEmpty());
  }

  @Test
  public void testDefaultIdentityIsNotNull() {
    ActivityClientOptions opts = ActivityClientOptions.newBuilder().build();
    assertNotNull(opts.getIdentity());
    assertFalse(opts.getIdentity().isEmpty());
  }

  @Test
  public void testSetNamespace() {
    ActivityClientOptions opts =
        ActivityClientOptions.newBuilder().setNamespace("my-namespace").build();
    assertEquals("my-namespace", opts.getNamespace());
  }

  @Test
  public void testSetDataConverter() {
    DataConverter dc = mock(DataConverter.class);
    ActivityClientOptions opts = ActivityClientOptions.newBuilder().setDataConverter(dc).build();
    assertSame(dc, opts.getDataConverter());
  }

  @Test
  public void testSetIdentity() {
    ActivityClientOptions opts =
        ActivityClientOptions.newBuilder().setIdentity("my-service").build();
    assertEquals("my-service", opts.getIdentity());
  }

  @Test
  public void testSetInterceptors() {
    ActivityClientCallsInterceptor interceptor = mock(ActivityClientCallsInterceptor.class);
    List<ActivityClientCallsInterceptor> interceptors = Collections.singletonList(interceptor);
    ActivityClientOptions opts =
        ActivityClientOptions.newBuilder().setInterceptors(interceptors).build();
    assertEquals(interceptors, opts.getInterceptors());
  }

  @Test
  public void testSetContextPropagators() {
    ContextPropagator propagator = mock(ContextPropagator.class);
    List<ContextPropagator> propagators = Collections.singletonList(propagator);
    ActivityClientOptions opts =
        ActivityClientOptions.newBuilder().setContextPropagators(propagators).build();
    assertEquals(propagators, opts.getContextPropagators());
  }

  @Test
  public void testToBuilderCopiesAllFields() {
    ActivityClientCallsInterceptor interceptor = mock(ActivityClientCallsInterceptor.class);
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
    ActivityClientCallsInterceptor interceptor = mock(ActivityClientCallsInterceptor.class);
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
  public void testEqualsAndHashCode() {
    DataConverter dc = mock(DataConverter.class);
    ActivityClientCallsInterceptor interceptor = mock(ActivityClientCallsInterceptor.class);

    ActivityClientOptions a =
        ActivityClientOptions.newBuilder()
            .setNamespace("ns")
            .setIdentity("id")
            .setDataConverter(dc)
            .setInterceptors(Collections.singletonList(interceptor))
            .build();

    ActivityClientOptions b =
        ActivityClientOptions.newBuilder()
            .setNamespace("ns")
            .setIdentity("id")
            .setDataConverter(dc)
            .setInterceptors(Collections.singletonList(interceptor))
            .build();

    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  public void testNotEqualDifferentNamespace() {
    ActivityClientOptions a =
        ActivityClientOptions.newBuilder().setNamespace("ns1").setIdentity("id").build();
    ActivityClientOptions b =
        ActivityClientOptions.newBuilder().setNamespace("ns2").setIdentity("id").build();
    assertNotEquals(a, b);
  }

  @Test
  public void testToStringContainsNamespace() {
    ActivityClientOptions opts =
        ActivityClientOptions.newBuilder().setNamespace("my-ns").setIdentity("id").build();
    assertTrue(opts.toString().contains("my-ns"));
  }

  @Test
  public void testGetDefaultInstanceHasDefaultNamespace() {
    assertEquals("default", ActivityClientOptions.getDefaultInstance().getNamespace());
  }

  @Test
  public void testMultipleInterceptors() {
    ActivityClientCallsInterceptor i1 = mock(ActivityClientCallsInterceptor.class);
    ActivityClientCallsInterceptor i2 = mock(ActivityClientCallsInterceptor.class);
    List<ActivityClientCallsInterceptor> interceptors = Arrays.asList(i1, i2);
    ActivityClientOptions opts =
        ActivityClientOptions.newBuilder().setInterceptors(interceptors).build();
    assertEquals(2, opts.getInterceptors().size());
    assertSame(i1, opts.getInterceptors().get(0));
    assertSame(i2, opts.getInterceptors().get(1));
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
