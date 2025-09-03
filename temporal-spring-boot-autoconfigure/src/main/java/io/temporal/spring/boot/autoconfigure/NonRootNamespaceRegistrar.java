package io.temporal.spring.boot.autoconfigure;

import com.google.common.base.MoreObjects;
import io.temporal.client.WorkflowClient;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.spring.boot.autoconfigure.properties.ConnectionProperties;
import io.temporal.spring.boot.autoconfigure.properties.NonRootNamespaceProperties;
import io.temporal.spring.boot.autoconfigure.properties.TemporalProperties;
import io.temporal.spring.boot.autoconfigure.template.ClientTemplate;
import io.temporal.spring.boot.autoconfigure.template.NamespaceTemplate;
import io.temporal.spring.boot.autoconfigure.template.WorkersTemplate;
import io.temporal.worker.WorkerFactory;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;

public class NonRootNamespaceRegistrar implements ImportBeanDefinitionRegistrar, EnvironmentAware {

  private static final Logger log = LoggerFactory.getLogger(NonRootNamespaceRegistrar.class);

  private Environment environment;

  @Override
  public void setEnvironment(Environment environment) {
    this.environment = environment;
  }

  @Override
  public void registerBeanDefinitions(
      AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {

    TemporalProperties temporalProperties = bindTemporalProperties();

    List<NonRootNamespaceProperties> namespaces = temporalProperties.getNamespaces();
    if (namespaces != null && !namespaces.isEmpty()) {
      log.info("Registering bean definitions for {} non-root namespaces", namespaces.size());

      namespaces.forEach(
          ns -> registerBeanDefinitionsForNamespace(registry, ns, temporalProperties));
    } else {
      log.debug("No non-root namespaces found in configuration - skipping bean registration");
    }
  }

  private TemporalProperties bindTemporalProperties() {
    try {
      return Binder.get(environment)
          .bind("spring.temporal", TemporalProperties.class)
          .orElse(createDefaultTemporalProperties());
    } catch (Exception e) {
      log.warn("Failed to bind TemporalProperties from environment: {}", e.getMessage());
      return createDefaultTemporalProperties();
    }
  }

  private TemporalProperties createDefaultTemporalProperties() {
    ConnectionProperties connection = new ConnectionProperties("localhost:7233", null, null, null);
    return new TemporalProperties(null, null, null, null, null, connection, null, null, null);
  }

  private void registerBeanDefinitionsForNamespace(
      BeanDefinitionRegistry registry,
      NonRootNamespaceProperties ns,
      TemporalProperties temporalProperties) {

    String beanPrefix = MoreObjects.firstNonNull(ns.getAlias(), ns.getNamespace());
    registerNamespaceFactoryBeanDefinition(registry, beanPrefix, ns, temporalProperties);

    registerWorkflowClientBeanDefinition(registry, beanPrefix);
    registerScheduleClientBeanDefinition(registry, beanPrefix);
    registerWorkerFactoryBeanDefinition(registry, beanPrefix);
    registerServiceStubsBeanDefinition(registry, beanPrefix);
    registerClientTemplateBeanDefinition(registry, beanPrefix);
    registerWorkersTemplateBeanDefinition(registry, beanPrefix);
    registerNamespaceTemplateBeanDefinition(registry, beanPrefix);
  }

  private void registerNamespaceFactoryBeanDefinition(
      BeanDefinitionRegistry registry,
      String beanPrefix,
      NonRootNamespaceProperties ns,
      TemporalProperties temporalProperties) {

    BeanDefinitionBuilder builder =
        BeanDefinitionBuilder.genericBeanDefinition(NonRootNamespaceFactory.class)
            .addConstructorArgValue(ns)
            .addConstructorArgValue(temporalProperties)
            .setScope("singleton");

    registry.registerBeanDefinition(beanPrefix + "NamespaceFactory", builder.getBeanDefinition());
  }

  private void registerWorkflowClientBeanDefinition(
      BeanDefinitionRegistry registry, String beanPrefix) {
    BeanDefinitionBuilder builder =
        BeanDefinitionBuilder.genericBeanDefinition(WorkflowClient.class)
            .setFactoryMethodOnBean("createWorkflowClient", beanPrefix + "NamespaceFactory");

    registry.registerBeanDefinition(beanPrefix + "WorkflowClient", builder.getBeanDefinition());
  }

  private void registerScheduleClientBeanDefinition(
      BeanDefinitionRegistry registry, String beanPrefix) {
    BeanDefinitionBuilder builder =
        BeanDefinitionBuilder.genericBeanDefinition(ScheduleClient.class)
            .setFactoryMethodOnBean("createScheduleClient", beanPrefix + "NamespaceFactory");

    registry.registerBeanDefinition(beanPrefix + "ScheduleClient", builder.getBeanDefinition());
  }

  private void registerWorkerFactoryBeanDefinition(
      BeanDefinitionRegistry registry, String beanPrefix) {
    BeanDefinitionBuilder builder =
        BeanDefinitionBuilder.genericBeanDefinition(WorkerFactory.class)
            .setFactoryMethodOnBean("createWorkerFactory", beanPrefix + "NamespaceFactory");

    registry.registerBeanDefinition(beanPrefix + "WorkerFactory", builder.getBeanDefinition());
  }

  private void registerServiceStubsBeanDefinition(
      BeanDefinitionRegistry registry, String beanPrefix) {
    BeanDefinitionBuilder builder =
        BeanDefinitionBuilder.genericBeanDefinition(WorkflowServiceStubs.class)
            .setFactoryMethodOnBean("createWorkflowServiceStubs", beanPrefix + "NamespaceFactory");

    registry.registerBeanDefinition(
        beanPrefix + "WorkflowServiceStubs", builder.getBeanDefinition());
  }

  private void registerClientTemplateBeanDefinition(
      BeanDefinitionRegistry registry, String beanPrefix) {
    BeanDefinitionBuilder builder =
        BeanDefinitionBuilder.genericBeanDefinition(ClientTemplate.class)
            .setFactoryMethodOnBean("createClientTemplate", beanPrefix + "NamespaceFactory");

    registry.registerBeanDefinition(beanPrefix + "ClientTemplate", builder.getBeanDefinition());
  }

  private void registerWorkersTemplateBeanDefinition(
      BeanDefinitionRegistry registry, String beanPrefix) {
    BeanDefinitionBuilder builder =
        BeanDefinitionBuilder.genericBeanDefinition(WorkersTemplate.class)
            .setFactoryMethodOnBean("createWorkersTemplate", beanPrefix + "NamespaceFactory");

    registry.registerBeanDefinition(beanPrefix + "WorkersTemplate", builder.getBeanDefinition());
  }

  private void registerNamespaceTemplateBeanDefinition(
      BeanDefinitionRegistry registry, String beanPrefix) {
    BeanDefinitionBuilder builder =
        BeanDefinitionBuilder.genericBeanDefinition(NamespaceTemplate.class)
            .setFactoryMethodOnBean("createNamespaceTemplate", beanPrefix + "NamespaceFactory");

    registry.registerBeanDefinition(beanPrefix + "NamespaceTemplate", builder.getBeanDefinition());
  }
}
