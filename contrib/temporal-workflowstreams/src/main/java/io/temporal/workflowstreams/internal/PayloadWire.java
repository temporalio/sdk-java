package io.temporal.workflowstreams.internal;

import com.google.protobuf.InvalidProtocolBufferException;
import io.temporal.api.common.v1.Payload;
import java.util.Base64;

/**
 * Encodes and decodes the base64-of-proto per-item wire format shared across the Go, Python, and
 * TypeScript workflow streams packages. Internal to the workflow streams module.
 */
public final class PayloadWire {
  /** Encodes a Payload to the base64-of-proto wire format. */
  public static String encode(Payload payload) {
    return Base64.getEncoder().encodeToString(payload.toByteArray());
  }

  /**
   * Decodes the base64-of-proto wire format back to a Payload.
   *
   * @throws IllegalArgumentException if the input is not valid base64 or not a valid Payload
   */
  public static Payload decode(String wire) {
    byte[] bytes = Base64.getDecoder().decode(wire);
    try {
      return Payload.parseFrom(bytes);
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalArgumentException("workflowstreams: unmarshal payload", e);
    }
  }

  /**
   * Estimates the contribution of a single encoded item to a poll response. {@code encoded} is
   * already base64 (its on-wire representation).
   */
  public static int wireSize(String encoded, String topic) {
    return encoded.length() + topic.length();
  }

  private PayloadWire() {}
}
