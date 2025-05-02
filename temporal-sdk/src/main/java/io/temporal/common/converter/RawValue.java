package io.temporal.common.converter;

import io.temporal.api.common.v1.Payload;
import java.util.Objects;

/**
 * RawValue is a representation of an unconverted, raw payload.
 *
 * <p>This type can be used as a parameter or return type in workflows and activities to pass
 * through a raw payload. Encoding/decoding of the payload is still done by the system.
 */
public final class RawValue {
  private final Payload payload;

  public RawValue(Payload payload) {
    this.payload = Objects.requireNonNull(payload);
  }

  public Payload getPayload() {
    return payload;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    RawValue rawValue = (RawValue) o;
    return Objects.equals(payload, rawValue.payload);
  }

  @Override
  public int hashCode() {
    return Objects.hash(payload);
  }

  @Override
  public String toString() {
    return "RawValue{" + "payload=" + payload + '}';
  }
}
