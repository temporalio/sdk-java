package io.temporal.spring.boot.autoconfigure.properties;

import javax.annotation.Nullable;

public class TestServerProperties {
  private final @Nullable Boolean enabled;

  public TestServerProperties(@Nullable Boolean enabled) {
    this.enabled = enabled;
  }

  @Nullable
  public Boolean getEnabled() {
    return enabled;
  }
}
