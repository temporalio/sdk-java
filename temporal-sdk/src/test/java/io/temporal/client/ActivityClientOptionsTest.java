package io.temporal.client;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.interceptors.ActivityClientInterceptor;
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

}
