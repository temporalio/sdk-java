package io.temporal.payload.codec;

import javax.annotation.Nullable;

/**
 * Optional capability opting a data converter into automatic payload-header codec processing.
 *
 * <p>The returned codec handles already serialized payloads, not application values. A custom
 * data-converter wrapper must implement and forward this capability if it wants to preserve its
 * delegate's capability. The SDK does not inspect opaque custom wrappers. Returning {@code null}
 * disables header processing without inspecting any value-conversion codec stage.
 *
 * <p>The codec must preserve one payload per header entry. Return a codec using the same
 * serialization context as the provider's data converter and support {@link
 * PayloadCodec#withContext}. SDK-owned wrappers forward the capability without applying value
 * conversion or external storage.
 */
public interface HeaderCodecProvider {
  /** Returns the codec-only stage, or {@code null} if no codec is available. */
  @Nullable
  PayloadCodec getHeaderCodec();
}
