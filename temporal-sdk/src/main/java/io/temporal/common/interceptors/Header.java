package io.temporal.common.interceptors;

import io.temporal.api.common.v1.Payload;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nonnull;

/**
 * Already serialized payload headers shared by interceptors and context propagators.
 *
 * <p>When automatic header codecs are enabled through {@link
 * io.temporal.common.converter.HeaderEncodingDataConverter}, outbound values must not already be
 * codec-encoded. Inbound interceptors receive decoded payloads. Use the underlying value converter
 * to construct entries, rather than serializing a {@link Payload} as an application value.
 */
public class Header {
  private final Map<String, Payload> values;

  public Header(@Nonnull io.temporal.api.common.v1.Header header) {
    values = header.getFieldsMap();
  }

  public static Header empty() {
    return new Header(new HashMap<>());
  }

  public Header(Map<String, Payload> values) {
    this.values = values;
  }

  public Map<String, Payload> getValues() {
    return values;
  }
}
