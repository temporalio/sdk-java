package io.temporal.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

public class AutoDiscoveryMixedPropertiesRejectedTest {

  @Test
  void testPackagesMixedWithNewPropertiesIsRejected() {
    assertThatThrownBy(
            () ->
                new SpringApplicationBuilder(Configuration.class)
                    .profiles("auto-discovery-mixed-packages-rejected")
                    .run()
                    .close())
        .satisfies(
            t -> {
              // Our IllegalStateException is wrapped by Spring's binding/condition machinery;
              // walk the cause chain to find it.
              Throwable cause = t;
              while (cause != null) {
                if (cause.getMessage() != null
                    && cause
                        .getMessage()
                        .contains("packages is deprecated and cannot be combined")) {
                  return;
                }
                cause = cause.getCause();
              }
              throw new AssertionError(
                  "Expected cause chain to contain 'packages is deprecated and cannot be combined',"
                      + " but it did not. Top-level message: "
                      + t.getMessage());
            });
  }

  @ComponentScan(
      excludeFilters =
          @ComponentScan.Filter(
              pattern = "io\\.temporal\\.spring\\.boot\\.autoconfigure\\.by.*",
              type = FilterType.REGEX))
  public static class Configuration {}
}
