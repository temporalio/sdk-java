package io.temporal.spring.boot.autoconfigure;

import com.google.common.base.MoreObjects;
import com.uber.m3.tally.Scope;
import io.opentracing.Tracer;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleClientOptions;
import io.temporal.common.converter.DataConverter;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.spring.boot.autoconfigure.properties.ConnectionProperties;
import io.temporal.spring.boot.autoconfigure.properties.NonRootNamespaceProperties;
import io.temporal.spring.boot.autoconfigure.properties.TemporalProperties;
import io.temporal.spring.boot.autoconfigure.template.ClientTemplate;
import io.temporal.spring.boot.autoconfigure.template.NamespaceTemplate;
import io.temporal.spring.boot.autoconfigure.template.NonRootNamespaceTemplate;
import io.temporal.spring.boot.autoconfigure.template.ServiceStubsTemplate;
import io.temporal.spring.boot.autoconfigure.template.TestWorkflowEnvironmentAdapter;
import io.temporal.spring.boot.autoconfigure.template.WorkersTemplate;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerFactoryOptions.Builder;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.WorkflowImplementationOptions;
import java.util.Optional;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanNotOfRequiredTypeException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;

@SuppressWarnings("unused")
public class NonRootNamespaceFactory implements BeanFactoryAware {

  private static final Logger log = LoggerFactory.getLogger(NonRootNamespaceFactory.class);

  private final NonRootNamespaceProperties namespaceProperties;
  private final TemporalProperties temporalProperties;
  private BeanFactory beanFactory;

  private NonRootNamespaceTemplate namespaceTemplate;
  private boolean initialized = false;

  public NonRootNamespaceFactory(
      NonRootNamespaceProperties namespaceProperties, TemporalProperties temporalProperties) {
    this.namespaceProperties = namespaceProperties;
    this.temporalProperties = temporalProperties;
  }

  @Override
  public void setBeanFactory(BeanFactory beanFactory) {
    this.beanFactory = beanFactory;
  }

  private synchronized void ensureInitialized() {
    if (!initialized) {
      initializeNamespaceTemplate();
      initialized = true;
    }
  }

  private void initializeNamespaceTemplate() {
    String beanPrefix =
        MoreObjects.firstNonNull(
            namespaceProperties.getAlias(), namespaceProperties.getNamespace());

    Scope metricsScope = findBean("temporalMetricsScope", Scope.class);
    Tracer tracer = findBean(Tracer.class);
    TestWorkflowEnvironmentAdapter testWorkflowEnvironment =
        findBean("temporalTestWorkflowEnvironment", TestWorkflowEnvironmentAdapter.class);

    DataConverter dataConverterByNamespace = findBeanByNamespace(beanPrefix, DataConverter.class);

    // Find customizers
    TemporalOptionsCustomizer<Builder> workFactoryCustomizer =
        findBeanByNameSpaceForTemporalCustomizer(beanPrefix, Builder.class);
    TemporalOptionsCustomizer<WorkflowServiceStubsOptions.Builder> workflowServiceStubsCustomizer =
        findBeanByNameSpaceForTemporalCustomizer(
            beanPrefix, WorkflowServiceStubsOptions.Builder.class);
    TemporalOptionsCustomizer<WorkerOptions.Builder> WorkerCustomizer =
        findBeanByNameSpaceForTemporalCustomizer(beanPrefix, WorkerOptions.Builder.class);
    TemporalOptionsCustomizer<WorkflowClientOptions.Builder> workflowClientCustomizer =
        findBeanByNameSpaceForTemporalCustomizer(beanPrefix, WorkflowClientOptions.Builder.class);
    TemporalOptionsCustomizer<ScheduleClientOptions.Builder> scheduleClientCustomizer =
        findBeanByNameSpaceForTemporalCustomizer(beanPrefix, ScheduleClientOptions.Builder.class);
    TemporalOptionsCustomizer<WorkflowImplementationOptions.Builder>
        workflowImplementationCustomizer =
            findBeanByNameSpaceForTemporalCustomizer(
                beanPrefix, WorkflowImplementationOptions.Builder.class);

    // it not set namespace connection properties, use root connection properties
    ConnectionProperties connectionProperties =
        MoreObjects.firstNonNull(
            namespaceProperties.getConnection(), temporalProperties.getConnection());

    ServiceStubsTemplate serviceStubsTemplate =
        new ServiceStubsTemplate(
            connectionProperties,
            metricsScope,
            testWorkflowEnvironment,
            workflowServiceStubsCustomizer);

    WorkflowServiceStubs workflowServiceStubs = serviceStubsTemplate.getWorkflowServiceStubs();

    namespaceTemplate =
        new NonRootNamespaceTemplate(
            beanFactory,
            namespaceProperties,
            workflowServiceStubs,
            dataConverterByNamespace,
            null, // Currently interceptors are not supported in non-root namespace
            null,
            null,
            tracer,
            testWorkflowEnvironment,
            workFactoryCustomizer,
            WorkerCustomizer,
            builder ->
                // Must make sure the namespace is set at the end of the builder chain
                Optional.ofNullable(workflowClientCustomizer)
                    .map(c -> c.customize(builder))
                    .orElse(builder)
                    .setNamespace(namespaceProperties.getNamespace()),
            scheduleClientCustomizer,
            workflowImplementationCustomizer);
  }

  public WorkflowClient createWorkflowClient() {
    ensureInitialized();
    return namespaceTemplate.getClientTemplate().getWorkflowClient();
  }

  public ScheduleClient createScheduleClient() {
    ensureInitialized();
    return namespaceTemplate.getClientTemplate().getScheduleClient();
  }

  public WorkerFactory createWorkerFactory() {
    ensureInitialized();
    return namespaceTemplate.getWorkersTemplate().getWorkerFactory();
  }

  public WorkflowServiceStubs createWorkflowServiceStubs() {
    ensureInitialized();
    return namespaceTemplate.getClientTemplate().getWorkflowClient().getWorkflowServiceStubs();
  }

  public ClientTemplate createClientTemplate() {
    ensureInitialized();
    return namespaceTemplate.getClientTemplate();
  }

  public WorkersTemplate createWorkersTemplate() {
    ensureInitialized();
    return namespaceTemplate.getWorkersTemplate();
  }

  public NamespaceTemplate createNamespaceTemplate() {
    ensureInitialized();
    return namespaceTemplate;
  }

  private <T> T findBeanByNamespace(String beanPrefix, Class<T> clazz) {
    try {
      return beanFactory.getBean(beanPrefix + clazz.getSimpleName(), clazz);
    } catch (NoSuchBeanDefinitionException ignore) {
      // Made non-namespace bean optional
    }
    return null;
  }

  private <T> @Nullable T findBean(Class<T> clazz) {
    try {
      return beanFactory.getBean(clazz);
    } catch (NoSuchBeanDefinitionException | BeanNotOfRequiredTypeException ignore) {
      // Ignore if the bean is not found or not of the required type
    }
    return null;
  }

  private <T> @Nullable T findBean(String beanName, Class<T> clazz) {
    try {
      return beanFactory.getBean(beanName, clazz);
    } catch (NoSuchBeanDefinitionException | BeanNotOfRequiredTypeException ignore) {
      // Ignore if the bean is not found or not of the required type
    }
    return null;
  }

  private <T> TemporalOptionsCustomizer<T> findBeanByNameSpaceForTemporalCustomizer(
      String beanPrefix, Class<T> genericOptionsBuilderClass) {
    String beanName =
        AutoConfigurationUtils.temporalCustomizerBeanName(beanPrefix, genericOptionsBuilderClass);
    try {
      TemporalOptionsCustomizer genericOptionsCustomizer =
          beanFactory.getBean(beanName, TemporalOptionsCustomizer.class);
      return (TemporalOptionsCustomizer<T>) genericOptionsCustomizer;
    } catch (BeansException e) {
      log.warn("No TemporalOptionsCustomizer found for {}. ", beanName);
      if (genericOptionsBuilderClass.isAssignableFrom(Builder.class)) {
        //        print tips once
        log.debug(
            "No TemporalOptionsCustomizer found for {}. \n You can add Customizer bean to do by namespace customization. \n "
                + "Note: bean name should start with namespace name and end with Customizer, and the middle part should be the customizer "
                + "target class name. \n "
                + "Example: @Bean(\"nsWorkerFactoryCustomizer\") is a customizer bean for WorkerFactory via "
                + "TemporalOptionsCustomizer<WorkerFactoryOptions.Builder>",
            genericOptionsBuilderClass.getSimpleName());
      }
      return null;
    }
  }
}
