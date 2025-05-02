package io.temporal.common.interceptors;

import io.temporal.api.common.v1.Payload;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nonnull;

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
