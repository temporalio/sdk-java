package io.temporal.spring.boot.autoconfigure;

import static org.junit.jupiter.api.Assertions.*;

import io.nexusrpc.ServiceDefinition;
import io.temporal.common.metadata.POJOActivityImplMetadata;
import io.temporal.common.metadata.POJOWorkflowImplMetadata;
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

@SpringBootTest(classes = RegisteredInfoTest.Configuration.class)
@ActiveProfiles(profiles = "auto-discovery-by-worker-name")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class RegisteredInfoTest {

  @Autowired ConfigurableApplicationContext applicationContext;

  @Autowired private WorkersTemplate workersTemplate;

  @BeforeEach
  void setUp() {
    applicationContext.start();
  }

  @Test
  @Timeout(value = 10)
  public void testRegisteredInfo() {
    assertNotNull(workersTemplate);
    assertNotNull(workersTemplate.getRegisteredInfo());
    Map<String, WorkersTemplate.RegisteredInfo> registeredInfoMap =
        workersTemplate.getRegisteredInfo();

    assertEquals(1, registeredInfoMap.size());
    registeredInfoMap.forEach(
        (taskQueue, info) -> {
          assertEquals("UnitTest", taskQueue);
          info.getRegisteredWorkflowInfo()
              .forEach(
                  (workflowInfo) -> {
                    assertNotNull(workflowInfo);
                    assertEquals(
                        "io.temporal.spring.boot.autoconfigure.byworkername.TestWorkflow",
                        workflowInfo.getClassName());
                    POJOWorkflowImplMetadata metadata = workflowInfo.getMetadata();
                    assertNotNull(metadata);
                    assertEquals(1, metadata.getWorkflowMethods().size());
                    assertEquals(1, metadata.getWorkflowInterfaces().size());
                    assertEquals(0, metadata.getSignalMethods().size());
                  });

          info.getRegisteredActivityInfo()
              .forEach(
                  (activityInfo) -> {
                    assertEquals(
                        "io.temporal.spring.boot.autoconfigure.bytaskqueue.TestActivityImpl",
                        activityInfo.getClassName());
                    assertEquals("TestActivityImpl", activityInfo.getBeanName());
                    POJOActivityImplMetadata metadata = activityInfo.getMetadata();
                    assertEquals(1, metadata.getActivityInterfaces().size());
                    assertEquals(1, metadata.getActivityMethods().size());
                    assertEquals(
                        "io.temporal.common.metadata.POJOActivityMethodMetadata",
                        metadata.getActivityMethods().get(0).getClass().getName());
                    assertEquals(
                        "Execute", metadata.getActivityMethods().get(0).getActivityTypeName());
                    assertEquals(
                        "execute", metadata.getActivityMethods().get(0).getMethod().getName());
                  });

          info.getRegisteredNexusServiceInfos()
              .forEach(
                  (nexusServiceInfo) -> {
                    assertEquals(
                        "io.temporal.spring.boot.autoconfigure.bytaskqueue.TestNexusServiceImpl",
                        nexusServiceInfo.getClassName());
                    assertEquals("TestNexusServiceImpl", nexusServiceInfo.getBeanName());
                    ServiceDefinition def = nexusServiceInfo.getDefinition();
                    assertEquals("TestNexusService", def.getName());
                    assertEquals(1, def.getOperations().size());
                    assertEquals("operation", def.getOperations().get("operation").getName());
                  });
        });
  }

  @ComponentScan(
      excludeFilters =
          @ComponentScan.Filter(
              pattern = "io\\.temporal\\.spring\\.boot\\.autoconfigure\\.byworkername\\..*",
              type = FilterType.REGEX))
  public static class Configuration {}
}
