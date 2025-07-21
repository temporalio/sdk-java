package io.temporal.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.temporal.client.WorkflowClient;
import javax.annotation.Resource;
import org.junit.jupiter.api.*;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = NonRootNamespaceBeanAvailabilityTest.TestConfiguration.class)
@ActiveProfiles(profiles = "multi-namespaces")
public class NonRootNamespaceBeanAvailabilityTest {

  @Autowired private ConfigurableApplicationContext applicationContext;
  @Autowired private ResourceInjectionComponent resourceInjectionComponent;
  @Autowired private AutowiredInjectionComponent autowiredInjectionComponent;
  @Autowired private ConstructorInjectionComponent constructorInjectionComponent;
  @Autowired private BeanAvailabilityValidator beanAvailabilityValidator;

  @Test
  public void shouldRegisterNonRootNamespaceBeansInContext() {
    assertThat(applicationContext.containsBean("ns1WorkflowClient")).isTrue();
    assertThat(applicationContext.containsBean("namespace2WorkflowClient")).isTrue();
  }

  @Test
  public void shouldHaveBeansAvailableForLookup() {
    WorkflowClient ns1Client =
        applicationContext.getBean("ns1WorkflowClient", WorkflowClient.class);
    WorkflowClient ns2Client =
        applicationContext.getBean("namespace2WorkflowClient", WorkflowClient.class);

    assertNotNull(ns1Client);
    assertNotNull(ns2Client);
    assertThat(ns1Client.getOptions().getNamespace()).isEqualTo("namespace1");
    assertThat(ns2Client.getOptions().getNamespace()).isEqualTo("namespace2");
  }

  @Test
  public void shouldInjectNonRootBeansWithResourceAnnotation() {
    assertNotNull(resourceInjectionComponent);
    assertNotNull(resourceInjectionComponent.getNs1WorkflowClient());
    assertThat(resourceInjectionComponent.getNs1WorkflowClient().getOptions().getNamespace())
        .isEqualTo("namespace1");
  }

  @Test
  public void shouldInjectNonRootBeansWithAutowiredAndQualifier() {
    assertNotNull(autowiredInjectionComponent);
    assertNotNull(autowiredInjectionComponent.getNs1WorkflowClient());
    assertThat(autowiredInjectionComponent.getNs1WorkflowClient().getOptions().getNamespace())
        .isEqualTo("namespace1");
  }

  @Test
  public void shouldInjectNonRootBeansWithConstructorInjection() {
    assertNotNull(constructorInjectionComponent);
    assertNotNull(constructorInjectionComponent.getNs1WorkflowClient());
    assertThat(constructorInjectionComponent.getNs1WorkflowClient().getOptions().getNamespace())
        .isEqualTo("namespace1");
  }

  @Test
  public void shouldHandleTimingIssueGracefully() {
    assertThat(beanAvailabilityValidator.isTimingIssueDetected())
        .isFalse()
        .as("No timing issues should be detected - Issue #2579 should be resolved");
  }

  @Configuration
  @EnableAutoConfiguration
  static class TestConfiguration {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public BeanAvailabilityValidator timingTestBeanPostProcessor() {
      return new BeanAvailabilityValidator();
    }

    @Bean
    @Profile("multi-namespaces")
    public ResourceInjectionComponent resourceInjectionComponent() {
      return new ResourceInjectionComponent();
    }

    @Bean
    @Profile("multi-namespaces")
    public AutowiredInjectionComponent autowiredInjectionComponent() {
      return new AutowiredInjectionComponent();
    }

    @Bean
    @Profile("multi-namespaces")
    public ConstructorInjectionComponent constructorInjectionComponent(
        @Qualifier("ns1WorkflowClient") WorkflowClient ns1WorkflowClient) {
      return new ConstructorInjectionComponent(ns1WorkflowClient);
    }
  }

  static class ResourceInjectionComponent {
    @Resource(name = "ns1WorkflowClient")
    private WorkflowClient ns1WorkflowClient;

    public WorkflowClient getNs1WorkflowClient() {
      return ns1WorkflowClient;
    }
  }

  static class AutowiredInjectionComponent {
    @Autowired
    @Qualifier("ns1WorkflowClient")
    private WorkflowClient ns1WorkflowClient;

    public WorkflowClient getNs1WorkflowClient() {
      return ns1WorkflowClient;
    }
  }

  static class ConstructorInjectionComponent {
    private final WorkflowClient ns1WorkflowClient;

    public ConstructorInjectionComponent(WorkflowClient ns1WorkflowClient) {
      this.ns1WorkflowClient = ns1WorkflowClient;
    }

    public WorkflowClient getNs1WorkflowClient() {
      return ns1WorkflowClient;
    }
  }

  static class BeanAvailabilityValidator implements BeanPostProcessor, BeanFactoryAware {
    private BeanFactory beanFactory;
    private boolean timingIssueDetected = false;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
      this.beanFactory = beanFactory;
    }

    @Override
    // Helps verify non-root namespace beans are accessible during bean post-processing
    public Object postProcessBeforeInitialization(Object bean, String beanName)
        throws BeansException {
      if (beanName.equals("resourceInjectionComponent")
          || beanName.equals("autowiredInjectionComponent")
          || beanName.equals("constructorInjectionComponent")) {
        try {
          WorkflowClient ns1Client = beanFactory.getBean("ns1WorkflowClient", WorkflowClient.class);
          if (!"namespace1".equals(ns1Client.getOptions().getNamespace())) {
            timingIssueDetected = true;
          }
        } catch (Exception e) {
          timingIssueDetected = true;
        }
      }
      return bean;
    }

    public boolean isTimingIssueDetected() {
      return timingIssueDetected;
    }
  }
}
