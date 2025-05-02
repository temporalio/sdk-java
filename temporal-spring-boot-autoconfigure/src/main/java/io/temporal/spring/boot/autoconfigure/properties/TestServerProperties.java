package io.temporal.spring.boot.autoconfigure.properties;

import javax.annotation.Nullable;
import org.springframework.boot.context.properties.ConstructorBinding;

public class TestServerProperties {
  private final @Nullable Boolean enabled;

  @ConstructorBinding
  public TestServerProperties(@Nullable Boolean enabled) {
    this.enabled = enabled;
  }

  @Nullable
  public Boolean getEnabled() {
    return enabled;
  }
}
