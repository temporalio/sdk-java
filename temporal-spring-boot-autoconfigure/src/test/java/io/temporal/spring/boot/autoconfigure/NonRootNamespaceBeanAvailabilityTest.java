package io.temporal.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = NonRootNamespaceBeanAvailabilityTest.Configuration.class)
@ActiveProfiles(profiles = "multi-namespaces")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class NonRootNamespaceBeanAvailabilityTest {

  @Autowired ConfigurableApplicationContext applicationContext;

  @BeforeEach
  void setUp() {
    applicationContext.start();
  }

  @Test
  @Timeout(value = 10)
  public void shouldRegisterNonRootNamespaceBeansInContext() {
    assertThat(applicationContext.containsBean("ns1WorkflowClient")).isTrue();
    assertThat(applicationContext.containsBean("namespace2WorkflowClient")).isTrue();
    assertThat(applicationContext.containsBean("ns1ScheduleClient")).isTrue();
    assertThat(applicationContext.containsBean("namespace2ScheduleClient")).isTrue();
    assertThat(applicationContext.containsBean("ns1WorkerFactory")).isTrue();
    assertThat(applicationContext.containsBean("namespace2WorkerFactory")).isTrue();
  }

  @Test
  @Timeout(value = 10)
  public void shouldRegisterNonRootNamespaceTemplateBeansInContext() {
    assertThat(applicationContext.containsBean("ns1NamespaceTemplate")).isTrue();
    assertThat(applicationContext.containsBean("namespace2NamespaceTemplate")).isTrue();
    assertThat(applicationContext.containsBean("ns1ClientTemplate")).isTrue();
    assertThat(applicationContext.containsBean("namespace2ClientTemplate")).isTrue();
    assertThat(applicationContext.containsBean("ns1WorkersTemplate")).isTrue();
    assertThat(applicationContext.containsBean("namespace2WorkersTemplate")).isTrue();
  }

  @Test
  @Timeout(value = 10)
  public void shouldHaveBeansAvailableForLookup() {
    assertThat(applicationContext.getBean("ns1WorkflowClient")).isNotNull();
    assertThat(applicationContext.getBean("namespace2WorkflowClient")).isNotNull();
    assertThat(applicationContext.getBean("ns1ScheduleClient")).isNotNull();
    assertThat(applicationContext.getBean("namespace2ScheduleClient")).isNotNull();
    assertThat(applicationContext.getBean("ns1WorkerFactory")).isNotNull();
    assertThat(applicationContext.getBean("namespace2WorkerFactory")).isNotNull();
  }

  @Test
  @Timeout(value = 10)
  public void applicationContextStartsSuccessfully() {
    assertThat(applicationContext.isRunning()).isTrue();
    assertThat(applicationContext.isActive()).isTrue();
  }

  @EnableAutoConfiguration
  public static class Configuration {}
}
