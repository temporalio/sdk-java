package io.temporal.spring.boot.autoconfigure.properties;

import java.util.List;
import javax.annotation.Nullable;

public class WorkersAutoDiscoveryProperties {
  private final @Nullable List<String> packages;

  public WorkersAutoDiscoveryProperties(@Nullable List<String> packages) {
    this.packages = packages;
  }

  @Nullable
  public List<String> getPackages() {
    return packages;
  }
}
