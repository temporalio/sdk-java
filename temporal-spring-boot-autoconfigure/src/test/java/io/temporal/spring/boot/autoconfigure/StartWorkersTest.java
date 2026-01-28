package io.temporal.spring.boot.autoconfigure;

import static org.junit.jupiter.api.Assertions.*;

import io.temporal.spring.boot.autoconfigure.properties.TemporalProperties;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = StartWorkersTest.Configuration.class)
@ActiveProfiles(profiles = "disable-start-workers")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class StartWorkersTest {
  @Autowired ConfigurableApplicationContext applicationContext;

  @Autowired TemporalProperties temporalProperties;

  @Autowired TestWorkflowEnvironment testWorkflowEnvironment;

  @Test
  @Timeout(value = 10)
  public void testStartWorkersConfigDisabled() {
    assertFalse(temporalProperties.getStartWorkers());
  }

  @Test
  @Timeout(value = 10)
  public void testWorkersStarted() {
    Worker worker = testWorkflowEnvironment.getWorkerFactory().getWorker("UnitTest");
    Assertions.assertFalse(applicationContext.containsBean("nonRootBeanPostProcessor"));
    assertNotNull(worker);
    assertTrue(worker.isSuspended());
  }

  @ComponentScan(
      excludeFilters =
          @ComponentScan.Filter(
              pattern = "io\\.temporal\\.spring\\.boot\\.autoconfigure\\.byworkername\\..*",
              type = FilterType.REGEX))
  public static class Configuration {}
}
