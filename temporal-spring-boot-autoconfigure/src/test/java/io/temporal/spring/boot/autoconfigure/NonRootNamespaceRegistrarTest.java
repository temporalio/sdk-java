package io.temporal.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.temporal.client.WorkflowClient;
import javax.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = NonRootNamespaceRegistrarTest.TestConfiguration.class)
@ActiveProfiles(profiles = "multi-namespaces")
public class NonRootNamespaceRegistrarTest {

  @Resource(name = "ns1WorkflowClient")
  private WorkflowClient ns1WorkflowClient;

  @Autowired private ApplicationContext applicationContext;

  @Test
  public void shouldInjectWorkflowClientUsingResource() {
    assertNotNull(ns1WorkflowClient, "WorkflowClient should be injected via @Resource");
    assertThat(ns1WorkflowClient.getOptions().getNamespace()).isEqualTo("namespace1");
  }

  @Test
  public void shouldRegisterBeansForMultipleNamespaces() {
    assertTrue(applicationContext.containsBean("ns1WorkflowClient"));
    assertTrue(applicationContext.containsBean("namespace2WorkflowClient"));
  }

  @Test
  public void shouldCreateDistinctBeanInstancesForDifferentNamespaces() {
    WorkflowClient ns1Client =
        applicationContext.getBean("ns1WorkflowClient", WorkflowClient.class);
    WorkflowClient ns2Client =
        applicationContext.getBean("namespace2WorkflowClient", WorkflowClient.class);

    assertThat(ns1Client).isNotSameAs(ns2Client);
    assertThat(ns1Client.getOptions().getNamespace()).isEqualTo("namespace1");
    assertThat(ns2Client.getOptions().getNamespace()).isEqualTo("namespace2");
  }

  @Configuration
  @EnableAutoConfiguration
  static class TestConfiguration {}
}
