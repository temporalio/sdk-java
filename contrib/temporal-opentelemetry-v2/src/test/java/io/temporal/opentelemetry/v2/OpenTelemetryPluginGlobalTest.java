package io.temporal.opentelemetry.v2;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.ContextStorage;
import io.temporal.opentelemetry.v2.internal.TemporalContextStorage;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class OpenTelemetryPluginGlobalTest {
  @Before
  @After
  public void resetGlobalOpenTelemetry() {
    GlobalOpenTelemetry.resetForTest();
  }

  @Test
  public void buildRejectsAnUnregisteredGlobal() {
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> OpenTelemetryPlugin.newBuilder().build());
    assertTrue(e.getMessage(), e.getMessage().contains("ReplaySafeOpenTelemetry"));
  }

  @Test
  public void buildWithAnUnregisteredGlobalStillAllowsRegistration() {
    assertThrows(IllegalStateException.class, () -> OpenTelemetryPlugin.newBuilder().build());
    try (ReplaySafeOpenTelemetry openTelemetry = ReplaySafeOpenTelemetry.newBuilder().build()) {
      GlobalOpenTelemetry.set(openTelemetry);
      OpenTelemetryPlugin.newBuilder().build();
    }
  }

  @Test
  public void buildAcceptsAReplaySafeGlobal() {
    try (ReplaySafeOpenTelemetry openTelemetry = ReplaySafeOpenTelemetry.newBuilder().build()) {
      GlobalOpenTelemetry.set(openTelemetry);
      OpenTelemetryPlugin.newBuilder().build();
    }
    assertTrue(ContextStorage.get() instanceof TemporalContextStorage);
  }
}
