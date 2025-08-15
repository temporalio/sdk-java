package io.temporal.client;

import com.google.common.base.Strings;
import io.temporal.common.Experimental;

/** Options for configuring the Temporal Nexus Service client. */
@Experimental
public class TemporalNexusServiceClientOptions {
  public static Builder newBuilder() {
    return new Builder();
  }

  private final String endpoint;

  TemporalNexusServiceClientOptions(String endpoint) {
    this.endpoint = endpoint;
  }

  public String getEndpoint() {
    return endpoint;
  }

  public static final class Builder {
    private String endpoint;

    public Builder setEndpoint(String endpoint) {
      this.endpoint = endpoint;
      return this;
    }

    public TemporalNexusServiceClientOptions build() {
      if (Strings.isNullOrEmpty(endpoint)) {
        throw new IllegalArgumentException("Must provide an endpoint");
      }

      return new TemporalNexusServiceClientOptions(endpoint);
    }
  }
}
