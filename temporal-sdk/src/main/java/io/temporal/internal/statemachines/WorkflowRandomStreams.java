package io.temporal.internal.statemachines;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import javax.annotation.Nonnull;

final class WorkflowRandomStreams {
  private static final String SEED_VERSION = "temporal.sdk.random.v1";

  private final Map<String, Random> streams = new HashMap<>();

  long deriveSeed(@Nonnull String runId, @Nonnull String name) {
    // The separators keep ("ab", "c") from colliding with ("a", "bc")
    String seed = String.join("\0", SEED_VERSION, runId, name);
    HashCode hash = Hashing.sha256().hashString(seed, StandardCharsets.UTF_8);
    return ByteBuffer.wrap(hash.asBytes()).getLong();
  }

  Random get(@Nonnull String runId, @Nonnull String name) {
    return streams.computeIfAbsent(name, key -> new Random(deriveSeed(runId, key)));
  }

  void reseed(@Nonnull String runId) {
    streams.forEach((name, stream) -> stream.setSeed(deriveSeed(runId, name)));
  }
}
