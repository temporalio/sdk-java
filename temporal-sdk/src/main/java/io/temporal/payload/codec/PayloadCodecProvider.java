package io.temporal.payload.codec;

import javax.annotation.Nullable;

/**
 * Exposes the codec-only stage of value conversion without enabling automatic header processing.
 *
 * <p>The stage handles already serialized payloads, not application values, and must preserve one
 * payload per header entry when used by {@link
 * io.temporal.common.converter.HeaderEncodingDataConverter}. It must support serialization
 * contexts.
 */
public interface PayloadCodecProvider {
  /** Returns the codec-only stage, or {@code null} if no codec is available. */
  @Nullable
  PayloadCodec getPayloadCodec();
}
