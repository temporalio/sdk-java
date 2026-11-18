package io.temporal.internal.nexus.system;

import static org.junit.Assert.*;

import io.temporal.common.converter.CodecDataConverter;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import java.util.Collections;
import java.util.concurrent.*;
import org.junit.Test;

public class SystemNexusConverterContextTest {
  @Test
  public void restoresNestedScopesAfterSuccessAndFailure() {
    DataConverter outer = DefaultDataConverter.STANDARD_INSTANCE;
    DataConverter inner = new CodecDataConverter(outer, Collections.emptyList());
    SystemNexusConverterContext.withDataConverter(
        outer,
        () -> {
          assertSame(outer, SystemNexusConverterContext.currentDataConverter());
          assertSame(
              inner,
              SystemNexusConverterContext.withDataConverter(
                  inner, SystemNexusConverterContext::currentDataConverter));
          assertSame(outer, SystemNexusConverterContext.currentDataConverter());
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  SystemNexusConverterContext.withDataConverter(
                      inner,
                      () -> {
                        throw new IllegalArgumentException("failure");
                      }));
          assertSame(outer, SystemNexusConverterContext.currentDataConverter());
          return null;
        });
    assertThrows(IllegalStateException.class, SystemNexusConverterContext::currentDataConverter);
    assertThrows(
        NullPointerException.class,
        () -> SystemNexusConverterContext.withDataConverter(null, () -> null));
    assertThrows(IllegalStateException.class, SystemNexusConverterContext::currentDataConverter);
  }

  @Test
  public void isolatesConcurrentConversionScopes() throws Exception {
    DataConverter first = DefaultDataConverter.STANDARD_INSTANCE;
    DataConverter second = new CodecDataConverter(first, Collections.emptyList());
    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<DataConverter> a = executor.submit(() -> scopedConverter(first, barrier));
      Future<DataConverter> b = executor.submit(() -> scopedConverter(second, barrier));
      assertSame(first, a.get(10, TimeUnit.SECONDS));
      assertSame(second, b.get(10, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
    }
    assertThrows(IllegalStateException.class, SystemNexusConverterContext::currentDataConverter);
  }

  private DataConverter scopedConverter(DataConverter converter, CyclicBarrier barrier) {
    DataConverter result =
        SystemNexusConverterContext.withDataConverter(
            converter,
            () -> {
              try {
                barrier.await(5, TimeUnit.SECONDS);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
              return SystemNexusConverterContext.currentDataConverter();
            });
    assertThrows(IllegalStateException.class, SystemNexusConverterContext::currentDataConverter);
    return result;
  }
}
