package io.temporal.spring.boot.autoconfigure;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.worker.WorkflowImplementationOptions.Builder;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = MultiNamespaceTest.Configuration.class)
@ActiveProfiles(profiles = "multi-namespaces")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MultiNamespaceTest {

  @Autowired ConfigurableApplicationContext applicationContext;

  @BeforeEach
  void setUp() {
    applicationContext.start();
  }

  @Test
  @Timeout(value = 10)
  public void shouldContainsNonRootRelatedBean() {
    Assertions.assertTrue(applicationContext.containsBean("nonRootBeanPostProcessor"));
    Assertions.assertTrue(applicationContext.containsBean("ns1NamespaceTemplate"));
    Assertions.assertTrue(applicationContext.containsBean("namespace2NamespaceTemplate"));
    Assertions.assertTrue(applicationContext.containsBean("ns1ClientTemplate"));
    Assertions.assertTrue(applicationContext.containsBean("namespace2ClientTemplate"));
    Assertions.assertTrue(applicationContext.containsBean("ns1WorkflowClient"));
    Assertions.assertTrue(applicationContext.containsBean("namespace2WorkflowClient"));
    Assertions.assertTrue(applicationContext.containsBean("ns1ScheduleClient"));
    Assertions.assertTrue(applicationContext.containsBean("namespace2ScheduleClient"));
    Assertions.assertTrue(applicationContext.containsBean("ns1WorkerFactory"));
    Assertions.assertTrue(applicationContext.containsBean("namespace2WorkerFactory"));

    String builderCanonicalName = Builder.class.getCanonicalName();
    String bindingCustomizerName = builderCanonicalName.replace("Options.Builder", "Customizer");
    bindingCustomizerName =
        bindingCustomizerName.substring(bindingCustomizerName.lastIndexOf(".") + 1);

    Map<String, TemporalOptionsCustomizer> beansOfType =
        applicationContext.getBeansOfType(TemporalOptionsCustomizer.class);
    Set<String> beanNames = beansOfType.keySet();
    Assertions.assertEquals(3, beansOfType.size());
    Assertions.assertTrue(beanNames.contains("ns1" + bindingCustomizerName));
    Assertions.assertTrue(beanNames.contains("namespace2" + bindingCustomizerName));
  }

  @EnableAutoConfiguration
  public static class Configuration {

    @Bean
    public TemporalOptionsCustomizer<WorkflowImplementationOptions.Builder>
        workflowImplementationCustomizer() {
      return getReturningMock();
    }

    @Bean
    public TemporalOptionsCustomizer<WorkflowImplementationOptions.Builder>
        ns1WorkflowImplementationCustomizer() {
      return getReturningMock();
    }

    @Bean
    public TemporalOptionsCustomizer<WorkflowImplementationOptions.Builder>
        namespace2WorkflowImplementationCustomizer() {
      return getReturningMock();
    }

    @SuppressWarnings("unchecked")
    private <T> TemporalOptionsCustomizer<T> getReturningMock() {
      return when(mock(TemporalOptionsCustomizer.class).customize(any()))
          .thenAnswer(invocation -> invocation.getArgument(0))
          .getMock();
    }
  }
}
