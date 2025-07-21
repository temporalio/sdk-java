package io.temporal.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.temporal.client.WorkflowClient;
import io.temporal.spring.boot.autoconfigure.properties.ConnectionProperties;
import io.temporal.spring.boot.autoconfigure.properties.NonRootNamespaceProperties;
import io.temporal.spring.boot.autoconfigure.properties.TemporalProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;

public class NonRootNamespaceFactoryTest {

  private NonRootNamespaceFactory factory;
  private BeanFactory mockBeanFactory;

  @BeforeEach
  void setUp() {
    NonRootNamespaceProperties namespaceProperties = createTestNamespaceProperties();
    TemporalProperties temporalProperties = createTestTemporalProperties();
    mockBeanFactory = mock(BeanFactory.class);

    factory = new NonRootNamespaceFactory(namespaceProperties, temporalProperties);
    factory.setBeanFactory(mockBeanFactory);
  }

  @Test
  public void shouldCreateWorkflowClientSuccessfully() {
    setupBasicMockBehavior();

    WorkflowClient client = factory.createWorkflowClient();

    assertNotNull(client);
    assertThat(client.getOptions().getNamespace()).isEqualTo("test-namespace");
  }

  @Test
  public void shouldCacheWorkflowClientAfterFirstCreation() {
    setupBasicMockBehavior();

    WorkflowClient client1 = factory.createWorkflowClient();
    WorkflowClient client2 = factory.createWorkflowClient();

    assertSame(client1, client2);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void shouldHandleMissingOptionalDependenciesGracefully() {
    when(mockBeanFactory.getBean(anyString(), any(Class.class)))
        .thenThrow(new NoSuchBeanDefinitionException("bean"));
    when(mockBeanFactory.getBean(any(Class.class)))
        .thenThrow(new NoSuchBeanDefinitionException("bean"));

    WorkflowClient client = factory.createWorkflowClient();
    assertNotNull(client);
  }

  private NonRootNamespaceProperties createTestNamespaceProperties() {
    return new NonRootNamespaceProperties(
        "testNs", "test-namespace", null, null, null, null, null, null);
  }

  private TemporalProperties createTestTemporalProperties() {
    ConnectionProperties connection = new ConnectionProperties("localhost:7233", null, null, null);
    return new TemporalProperties(null, null, null, null, null, connection, null, null, null);
  }

  @SuppressWarnings("unchecked")
  private void setupBasicMockBehavior() {
    when(mockBeanFactory.getBean(anyString(), any(Class.class))).thenReturn(null);
    when(mockBeanFactory.getBean(any(Class.class))).thenReturn(null);
  }
}
