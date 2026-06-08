package io.temporal.client;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

import io.temporal.common.converter.DataConverter;
import io.temporal.common.interceptors.NexusClientInterceptor;
import java.util.Collections;
import org.junit.Test;

public class NexusClientOptionsTest {

  @Test
  public void testDefaultNamespaceIsDefault() {
    NexusClientOptions opts = NexusClientOptions.newBuilder().build();
    assertEquals("default", opts.getNamespace());
  }

  @Test
  public void testDefaultIdentityIsNotNull() {
    NexusClientOptions opts = NexusClientOptions.newBuilder().build();
    assertNotNull(opts.getIdentity());
    assertFalse(opts.getIdentity().isEmpty());
  }

  @Test
  public void testNewBuilderFromOptionsCopiesAllFields() {
    NexusClientInterceptor interceptor = mock(NexusClientInterceptor.class);
    DataConverter dc = mock(DataConverter.class);

    NexusClientOptions original =
        NexusClientOptions.newBuilder()
            .setNamespace("ns")
            .setIdentity("id")
            .setDataConverter(dc)
            .setInterceptors(Collections.singletonList(interceptor))
            .build();

    NexusClientOptions copy = NexusClientOptions.newBuilder(original).build();

    assertEquals(original.getNamespace(), copy.getNamespace());
    assertEquals(original.getIdentity(), copy.getIdentity());
    assertSame(original.getDataConverter(), copy.getDataConverter());
    assertEquals(original.getInterceptors(), copy.getInterceptors());
  }
}
