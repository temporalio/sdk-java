package io.temporal.spring.boot.autoconfigure;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

public class WorkerVersioningMissingAnnotationTest {

  @Test
  void testFailsToLoad() {
    BeanCreationException e =
        assertThrows(
            BeanCreationException.class,
            () -> {
              try (ConfigurableApplicationContext ignored =
                  new SpringApplicationBuilder(Configuration.class)
                      .profiles("worker-versioning-missing-annotation")
                      .run()) {
                fail("Should not load");
              }
            });
    assertThat(e).hasMessageContaining("must have a VersioningBehavior set");
  }

  @ComponentScan(
      excludeFilters =
          @ComponentScan.Filter(
              pattern = "io\\.temporal\\.spring\\.boot\\.autoconfigure\\.by.*",
              type = FilterType.REGEX))
  public static class Configuration {}
}
