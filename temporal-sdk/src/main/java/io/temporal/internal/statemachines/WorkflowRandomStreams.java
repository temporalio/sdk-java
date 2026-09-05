package io.temporal.internal.statemachines;

import io.temporal.internal.sync.WorkflowInternal;
import io.temporal.workflow.WorkflowRandomStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

final class WorkflowRandomStreams {
  private static final byte[] SEED_VERSION =
      "temporal.sdk.random.v1".getBytes(StandardCharsets.UTF_8);

  private final Map<String, Stream> streams = new HashMap<>();
  private String runId;

  WorkflowRandomStream get(String name) {
    Objects.requireNonNull(name, "name");
    if (runId == null) {
      throw new IllegalStateException("Workflow Run ID is not initialized");
    }
    return streams.computeIfAbsent(name, key -> new Stream(deriveSeed(runId, key)));
  }

  void updateRunId(String runId) {
    this.runId = Objects.requireNonNull(runId, "runId");
    streams.forEach((name, stream) -> stream.reseed(deriveSeed(runId, name)));
  }

  static byte[] deriveSeed(String runId, String name) {
    MessageDigest digest = newSha256();
    digest.update(SEED_VERSION);
    digest.update((byte) 0);
    digest.update(runId.getBytes(StandardCharsets.UTF_8));
    digest.update((byte) 0);
    digest.update(name.getBytes(StandardCharsets.UTF_8));
    return digest.digest();
  }

  private static MessageDigest newSha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  private static final class Stream implements WorkflowRandomStream {
    private final MessageDigest digest = newSha256();
    private byte[] seed;
    private byte[] block = new byte[0];
    private int blockOffset;
    private long counter;

    private Stream(byte[] seed) {
      reseed(seed);
    }

    @Override
    public void nextBytes(byte[] bytes) {
      WorkflowInternal.assertNotReadOnly("random");
      Objects.requireNonNull(bytes, "bytes");
      int outputOffset = 0;
      while (outputOffset < bytes.length) {
        if (blockOffset == block.length) {
          refill();
        }
        int length = Math.min(bytes.length - outputOffset, block.length - blockOffset);
        System.arraycopy(block, blockOffset, bytes, outputOffset, length);
        blockOffset += length;
        outputOffset += length;
      }
    }

    @Override
    public long nextLong() {
      WorkflowInternal.assertNotReadOnly("random");
      long value = 0;
      for (int i = 0; i < Long.BYTES; i++) {
        if (blockOffset == block.length) {
          refill();
        }
        value = (value << Byte.SIZE) | (block[blockOffset++] & 0xffL);
      }
      return value;
    }

    private void refill() {
      digest.reset();
      digest.update(seed);
      for (int shift = Long.SIZE - Byte.SIZE; shift >= 0; shift -= Byte.SIZE) {
        digest.update((byte) (counter >>> shift));
      }
      counter++;
      block = digest.digest();
      blockOffset = 0;
    }

    private void reseed(byte[] seed) {
      this.seed = Arrays.copyOf(seed, seed.length);
      block = new byte[0];
      blockOffset = 0;
      counter = 0;
    }
  }
}
