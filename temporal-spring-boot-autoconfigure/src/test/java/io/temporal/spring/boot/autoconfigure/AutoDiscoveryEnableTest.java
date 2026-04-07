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
 * Verifies that {@code workers-auto-discovery.enable: true} auto-registers {@code @WorkflowImpl}
 * and {@code @ActivityImpl} Spring beans without any package scanning configured.
 */
@SpringBootTest(classes = AutoDiscoveryEnableTest.Configuration.class)
@ActiveProfiles(profiles = "auto-discovery-enable")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class AutoDiscoveryEnableTest {

  @Autowired ConfigurableApplicationContext applicationContext;

  @Autowired private WorkersTemplate workersTemplate;

  @BeforeEach
  void setUp() {
    applicationContext.start();
  }

  @Test
  @Timeout(value = 10)
  public void testEnableRegistersWorkflowAndActivityBeans() {
    assertNotNull(workersTemplate);
    Map<String, WorkersTemplate.RegisteredInfo> registeredInfoMap =
        workersTemplate.getRegisteredInfo();

    assertEquals(1, registeredInfoMap.size());
    WorkersTemplate.RegisteredInfo info = registeredInfoMap.get("UnitTest");
    assertNotNull(info, "Expected worker on task queue 'UnitTest'");

    // @WorkflowImpl Spring bean registered via enable: true (no packages configured)
    assertEquals(
        1,
        info.getRegisteredWorkflowInfo().size(),
        "@WorkflowImpl bean should be registered via enable: true without packages");
    assertEquals(
        "io.temporal.spring.boot.autoconfigure.byenable.TestWorkflow",
        info.getRegisteredWorkflowInfo().get(0).getClassName());

    // @ActivityImpl bean registered via enable: true
    assertEquals(
        1,
        info.getRegisteredActivityInfo().size(),
        "@ActivityImpl bean should be registered via enable: true");
    assertEquals(
        "io.temporal.spring.boot.autoconfigure.byenable.TestActivityImpl",
        info.getRegisteredActivityInfo().get(0).getClassName());
  }

  // Exclude all test sub-packages except byenable to avoid interference from other test beans
  @ComponentScan(
      excludeFilters =
          @ComponentScan.Filter(
              pattern =
                  "io\\.temporal\\.spring\\.boot\\.autoconfigure\\."
                      + "(bytaskqueue|byworkername|workerversioning)\\..*",
              type = FilterType.REGEX))
  public static class Configuration {}
}
