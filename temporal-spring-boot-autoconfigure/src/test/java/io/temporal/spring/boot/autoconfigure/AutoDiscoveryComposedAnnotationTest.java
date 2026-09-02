package io.temporal.spring.boot.autoconfigure;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.spring.boot.autoconfigure.composedannotation.ComposedAnnotatedActivityImpl;
import io.temporal.spring.boot.autoconfigure.composedannotation.ComposedAnnotatedWorkflow;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = AutoDiscoveryComposedAnnotationTest.Configuration.class)
@ActiveProfiles(profiles = "auto-discovery-composed-annotation")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class AutoDiscoveryComposedAnnotationTest {
  @Autowired ConfigurableApplicationContext applicationContext;

  @Autowired WorkflowClient workflowClient;

  @BeforeEach
  void setUp() {
    applicationContext.start();
  }

  @Test
  @Timeout(value = 10)
  public void testAutoDiscoveryViaComposedAnnotation() {
    ComposedAnnotatedWorkflow workflow =
        workflowClient.newWorkflowStub(
            ComposedAnnotatedWorkflow.class,
            WorkflowOptions.newBuilder().setTaskQueue("UnitTest").build());
    Assertions.assertEquals("composed:composed-activity:hi", workflow.execute("hi"));
  }

  @ComponentScan(
      excludeFilters =
          @ComponentScan.Filter(
              pattern =
                  "io\\.temporal\\.spring\\.boot\\.autoconfigure\\.(bytaskqueue|byworkername)\\..*",
              type = FilterType.REGEX))
  public static class Configuration {

    // Not using @Component so that it stays scoped to this test
    @Bean
    public ComposedAnnotatedActivityImpl composedAnnotatedActivityImpl() {
      return new ComposedAnnotatedActivityImpl();
    }
  }
}
