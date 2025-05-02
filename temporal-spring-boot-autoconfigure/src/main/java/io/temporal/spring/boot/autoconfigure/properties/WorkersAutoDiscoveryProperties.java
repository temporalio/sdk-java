package io.temporal.spring.boot.autoconfigure.properties;

import java.util.List;
import javax.annotation.Nullable;
import org.springframework.boot.context.properties.ConstructorBinding;

public class WorkersAutoDiscoveryProperties {
  private final @Nullable List<String> packages;

  @ConstructorBinding
  public WorkersAutoDiscoveryProperties(@Nullable List<String> packages) {
    this.packages = packages;
  }

  @Nullable
  public List<String> getPackages() {
    return packages;
  }
}
