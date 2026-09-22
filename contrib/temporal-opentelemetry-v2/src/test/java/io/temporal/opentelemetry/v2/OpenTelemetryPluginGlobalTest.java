package io.temporal.opentelemetry.v2;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.opentelemetry.api.GlobalOpenTelemetry;
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
  public void buildAcceptsAReplaySafeGlobal() {
    try (ReplaySafeOpenTelemetry openTelemetry = ReplaySafeOpenTelemetry.newBuilder().build()) {
      GlobalOpenTelemetry.set(openTelemetry);
      OpenTelemetryPlugin.newBuilder().build();
    }
  }
}
