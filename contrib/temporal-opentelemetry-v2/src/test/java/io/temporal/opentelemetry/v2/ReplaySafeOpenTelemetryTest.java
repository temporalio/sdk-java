package io.temporal.opentelemetry.v2;

import static org.junit.Assert.assertThrows;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Context;
import org.junit.Test;

public class ReplaySafeOpenTelemetryTest {
  @Test
  public void pluginRejectsContextUsedBeforeReplaySafeOpenTelemetry() {
    Context.current();

    try (ReplaySafeOpenTelemetry openTelemetry = ReplaySafeOpenTelemetry.newBuilder().build()) {
      GlobalOpenTelemetry.set(openTelemetry);
      assertThrows(IllegalStateException.class, () -> OpenTelemetryPlugin.newBuilder().build());
    }
  }
}
