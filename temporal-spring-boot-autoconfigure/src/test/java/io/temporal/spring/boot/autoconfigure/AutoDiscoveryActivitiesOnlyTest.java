package io.temporal.spring.boot.autoconfigure;

import static org.junit.jupiter.api.Assertions.*;

import io.temporal.spring.boot.autoconfigure.template.WorkersTemplate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.ActiveProfiles;

/**
 * Regression test for https://github.com/temporalio/sdk-java/issues/2780:
 * {@code @ActivityImpl}-annotated beans should be auto-registered with workers even when no
 * workflow packages are configured under {@code spring.temporal.workers-auto-discovery.packages}.
 */
@SpringBootTest(classes = AutoDiscoveryActivitiesOnlyTest.Configuration.class)
@ActiveProfiles(profiles = "auto-discovery-activities-only")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class AutoDiscoveryActivitiesOnlyTest {

  @Autowired ConfigurableApplicationContext applicationContext;

  @Autowired private WorkersTemplate workersTemplate;

  @BeforeEach
  void setUp() {
    applicationContext.start();
  }

  @Test
  @Timeout(value = 10)
  public void testActivityBeansRegisteredWithoutWorkflowPackages() {
    assertNotNull(workersTemplate);
    Map<String, WorkersTemplate.RegisteredInfo> registeredInfoMap =
        workersTemplate.getRegisteredInfo();

    // One worker should have been created for the task queue specified in @ActivityImpl
    assertEquals(1, registeredInfoMap.size());
    registeredInfoMap.forEach(
        (taskQueue, info) -> {
          assertEquals("UnitTest", taskQueue);

          // No workflow packages configured, so no workflows should be registered
          assertTrue(
              info.getRegisteredWorkflowInfo().isEmpty(),
              "No workflows expected when packages: [] is configured");

          // @ActivityImpl bean should be registered despite no packages being configured
          assertFalse(
              info.getRegisteredActivityInfo().isEmpty(),
              "@ActivityImpl beans should be auto-registered without workflow packages");
          assertEquals(1, info.getRegisteredActivityInfo().size());
          assertEquals(
              "io.temporal.spring.boot.autoconfigure.bytaskqueue.TestActivityImpl",
              info.getRegisteredActivityInfo().get(0).getClassName());

          // @NexusServiceImpl bean should also be registered
          assertFalse(
              info.getRegisteredNexusServiceInfos().isEmpty(),
              "@NexusServiceImpl beans should be auto-registered without workflow packages");
          assertEquals(1, info.getRegisteredNexusServiceInfos().size());
        });
  }

  @ComponentScan(
      excludeFilters =
          @ComponentScan.Filter(
              pattern = "io\\.temporal\\.spring\\.boot\\.autoconfigure\\.byworkername\\..*",
              type = FilterType.REGEX))
  public static class Configuration {}
}
