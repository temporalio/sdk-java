package io.temporal.common.interceptors;

import io.temporal.api.common.v1.Payload;
import io.temporal.internal.common.HeaderUtils;
import io.temporal.payload.codec.PayloadCodec;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Already serialized payload headers shared by interceptors and context propagators.
 *
 * <p>Entries supplied through {@link #getValues()} should be wire-ready, including any codec
 * encoding. Tracing and context propagation may separately opt into SDK codec processing of their
 * own output. Encoding opt-in belongs to a key and payload instance: removing and reinserting that
 * same instance retains its opt-in. Use {@link #putAll(Map, boolean)} with {@code false} to
 * explicitly clear it.
 */
public class Header {
  private final Map<String, Payload> values;
  private final Map<String, Payload> codecValues = new HashMap<>();
  private final @Nullable PayloadCodec codec;

  public Header(@Nonnull io.temporal.api.common.v1.Header header) {
    this(header, null);
  }

  public Header(@Nonnull io.temporal.api.common.v1.Header header, @Nullable PayloadCodec codec) {
    this.values = header.getFieldsMap();
    this.codec = codec;
  }

  public static Header empty() {
    return new Header(new HashMap<>());
  }

  public Header(Map<String, Payload> values) {
    this.values = values;
    this.codec = null;
  }

  public Map<String, Payload> getValues() {
    return values;
  }

  /** Used by opted-in header producers; ordinary interceptor entries remain wire-ready. */
  public void putAll(Map<String, Payload> payloads, boolean encode) {
    values.putAll(payloads);
    payloads.keySet().forEach(codecValues::remove);
    if (encode) {
      codecValues.putAll(payloads);
    }
  }

  /** Returns whether this producer requested codec encoding for an entry. */
  public boolean shouldEncode(String key) {
    return codecValues.containsKey(key) && codecValues.get(key) == values.get(key);
  }

  /** Returns a private decoded view for an opted-in tracing or propagation consumer. */
  public Map<String, Payload> getCodecDecodedValues() {
    return new HashMap<>(
        HeaderUtils.decode(
                io.temporal.api.common.v1.Header.newBuilder().putAllFields(values).build(), codec)
            .getFieldsMap());
  }
}
