package io.temporal.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class TemporalDevServerOptionsTest {
  @Test
  void copiesAndDefensivelyCopiesExtraArguments() {
    List<String> args = new ArrayList<>(Arrays.asList("--one", "value"));
    TemporalDevServerOptions original =
        TemporalDevServerOptions.newBuilder()
            .setDownloadVersion("arbitrary-fixed-version")
            .setExtraArgs(args)
            .build();
    args.add("--mutated");

    TemporalDevServerOptions copy =
        TemporalDevServerOptions.newBuilder(original).setUiEnabled(true).build();

    assertEquals(Arrays.asList("--one", "value"), original.getExtraArgs());
    assertEquals(original.getExtraArgs(), copy.getExtraArgs());
    assertNotSame(original.getExtraArgs(), copy.getExtraArgs());
    assertThrows(UnsupportedOperationException.class, () -> copy.getExtraArgs().add("no"));
    assertTrue(copy.isUiEnabled());
    assertFalse(original.isUiEnabled());
  }

  @Test
  void validatesValues() {
    assertThrows(
        IllegalArgumentException.class,
        () -> TemporalDevServerOptions.newBuilder().setDownloadVersion(" ").build());
    assertThrows(
        IllegalArgumentException.class,
        () -> TemporalDevServerOptions.newBuilder().setPort(0).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> TemporalDevServerOptions.newBuilder().setUiPort(65536).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> TemporalDevServerOptions.newBuilder().setStartupTimeout(Duration.ZERO).build());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TemporalDevServerOptions.newBuilder()
                .setDownloadCacheTtl(Duration.ofSeconds(-1))
                .build());
    assertThrows(
        IllegalArgumentException.class,
        () -> TemporalDevServerOptions.newBuilder().setExtraArgs("bad\nargument").build());
  }
}
