package io.temporal.opentelemetry.v2;

import static org.junit.Assert.assertTrue;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextStorageProvider;
import io.temporal.opentelemetry.v2.internal.TemporalContextStorageProvider;
import java.util.ServiceLoader;
import org.junit.Test;

public class ReplaySafeOpenTelemetryTest {
  @Test
  public void buildsAfterContextStorageIsInitialized() {
    boolean providerRegistered = false;
    for (ContextStorageProvider provider : ServiceLoader.load(ContextStorageProvider.class)) {
      if (provider instanceof TemporalContextStorageProvider) {
        providerRegistered = true;
        break;
      }
    }
    assertTrue(providerRegistered);

    Context.current();

    try (ReplaySafeOpenTelemetry first = ReplaySafeOpenTelemetry.newBuilder().build();
        ReplaySafeOpenTelemetry second = ReplaySafeOpenTelemetry.newBuilder().build()) {}
  }
}
