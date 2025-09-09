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
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanNotOfRequiredTypeException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

public class NonRootBeanPostProcessor implements BeanPostProcessor, BeanFactoryAware {

  private static final Logger log = LoggerFactory.getLogger(NonRootBeanPostProcessor.class);

  private ConfigurableListableBeanFactory beanFactory;

  private final @Nonnull TemporalProperties temporalProperties;
  private final @Nullable List<NonRootNamespaceProperties> namespaceProperties;
  private @Nullable Tracer tracer;
  private @Nullable TestWorkflowEnvironmentAdapter testWorkflowEnvironment;
  private @Nullable Scope metricsScope;

  public NonRootBeanPostProcessor(@Nonnull TemporalProperties temporalProperties) {
    this.temporalProperties = temporalProperties;
    this.namespaceProperties = temporalProperties.getNamespaces();
  }

  @Override
  public Object postProcessAfterInitialization(@Nonnull Object bean, @Nonnull String beanName)
      throws BeansException {
    if (bean instanceof NamespaceTemplate && beanName.equals("temporalRootNamespaceTemplate")) {
      if (namespaceProperties != null) {
        // If there are non-root namespaces, we need to inject beans for each of them. Look
        // up the bean manually instead of using @Autowired to avoid circular dependencies or
        // causing the dependency to
        // get initialized to early and skip post-processing.
        //
        // Note: We don't use @Lazy here because these dependencies are optional and @Lazy doesn't
        // interact well with
        // optional dependencies.
        metricsScope = findBean("temporalMetricsScope", Scope.class);
        tracer = findBean(Tracer.class);
        testWorkflowEnvironment =
            findBean("temporalTestWorkflowEnvironment", TestWorkflowEnvironmentAdapter.class);
        namespaceProperties.forEach(this::injectBeanByNonRootNamespace);
      }
    }
    return bean;
  }

  private void injectBeanByNonRootNamespace(NonRootNamespaceProperties ns) {
    String beanPrefix = MoreObjects.firstNonNull(ns.getAlias(), ns.getNamespace());
    DataConverter dataConverterByNamespace = findBeanByNamespace(beanPrefix, DataConverter.class);

    // found regarding namespace customizer bean, it can be optional
    List<TemporalOptionsCustomizer<Builder>> workFactoryCustomizers =
        findBeanByNameSpaceForTemporalCustomizer(beanPrefix, Builder.class);
    List<TemporalOptionsCustomizer<WorkflowServiceStubsOptions.Builder>>
        workflowServiceStubsCustomizers =
            findBeanByNameSpaceForTemporalCustomizer(
                beanPrefix, WorkflowServiceStubsOptions.Builder.class);
    List<TemporalOptionsCustomizer<WorkerOptions.Builder>> workerCustomizers =
        findBeanByNameSpaceForTemporalCustomizer(beanPrefix, WorkerOptions.Builder.class);
    List<TemporalOptionsCustomizer<WorkflowClientOptions.Builder>> workflowClientCustomizers =
        findBeanByNameSpaceForTemporalCustomizer(beanPrefix, WorkflowClientOptions.Builder.class);
    if (workflowClientCustomizers != null) {
      workflowClientCustomizers =
          workflowClientCustomizers.stream()
              .map(
                  c ->
                      (TemporalOptionsCustomizer<WorkflowClientOptions.Builder>)
                          (WorkflowClientOptions.Builder o) ->
                              c.customize(o).setNamespace(ns.getNamespace()))
              .collect(Collectors.toList());
    }
    List<TemporalOptionsCustomizer<ScheduleClientOptions.Builder>> scheduleClientCustomizers =
        findBeanByNameSpaceForTemporalCustomizer(beanPrefix, ScheduleClientOptions.Builder.class);
    List<TemporalOptionsCustomizer<WorkflowImplementationOptions.Builder>>
        workflowImplementationCustomizers =
            findBeanByNameSpaceForTemporalCustomizer(
                beanPrefix, WorkflowImplementationOptions.Builder.class);

    // it not set namespace connection properties, use root connection properties
    ConnectionProperties connectionProperties =
        MoreObjects.firstNonNull(ns.getConnection(), temporalProperties.getConnection());
    ServiceStubsTemplate serviceStubsTemplate =
        new ServiceStubsTemplate(
            connectionProperties,
            metricsScope,
            testWorkflowEnvironment,
            workflowServiceStubsCustomizers);
    WorkflowServiceStubs workflowServiceStubs = serviceStubsTemplate.getWorkflowServiceStubs();

    NonRootNamespaceTemplate namespaceTemplate =
        new NonRootNamespaceTemplate(
            beanFactory,
            ns,
            workflowServiceStubs,
            dataConverterByNamespace,
            null, // Currently interceptors are not supported in non-root namespace
            null,
            null,
            tracer,
            testWorkflowEnvironment,
            workFactoryCustomizers,
            workerCustomizers,
            workflowClientCustomizers,
            scheduleClientCustomizers,
            workflowImplementationCustomizers);

    ClientTemplate clientTemplate = namespaceTemplate.getClientTemplate();
    WorkflowClient workflowClient = clientTemplate.getWorkflowClient();
    ScheduleClient scheduleClient = clientTemplate.getScheduleClient();
    WorkersTemplate workersTemplate = namespaceTemplate.getWorkersTemplate();
    WorkerFactory workerFactory = workersTemplate.getWorkerFactory();

    // register beans by namespace
    beanFactory.registerSingleton(
        beanPrefix + ServiceStubsTemplate.class.getSimpleName(), serviceStubsTemplate);
    beanFactory.registerSingleton(
        beanPrefix + WorkflowServiceStubs.class.getSimpleName(), workflowServiceStubs);
    beanFactory.registerSingleton(
        beanPrefix + NamespaceTemplate.class.getSimpleName(), namespaceTemplate);
    beanFactory.registerSingleton(
        beanPrefix + ClientTemplate.class.getSimpleName(), namespaceTemplate.getClientTemplate());
    beanFactory.registerSingleton(
        beanPrefix + WorkersTemplate.class.getSimpleName(), workersTemplate);
    beanFactory.registerSingleton(
        beanPrefix + WorkflowClient.class.getSimpleName(), workflowClient);
    beanFactory.registerSingleton(
        beanPrefix + ScheduleClient.class.getSimpleName(), scheduleClient);
    beanFactory.registerSingleton(beanPrefix + WorkerFactory.class.getSimpleName(), workerFactory);
  }

  @Override
  public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
    this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
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

  private <T> List<TemporalOptionsCustomizer<T>> findBeanByNameSpaceForTemporalCustomizer(
      String beanPrefix, Class<T> genericOptionsBuilderClass) {
    String beanName =
        AutoConfigurationUtils.temporalCustomizerBeanName(beanPrefix, genericOptionsBuilderClass);
    try {
      // TODO(https://github.com/temporalio/sdk-java/issues/2638): Support multiple customizers in
      // the non root namespace
      TemporalOptionsCustomizer<T> genericOptionsCustomizer =
          beanFactory.getBean(beanName, TemporalOptionsCustomizer.class);
      return Collections.singletonList(genericOptionsCustomizer);
    } catch (BeansException e) {
      log.warn("No TemporalOptionsCustomizer found for {}. ", beanName);
      if (genericOptionsBuilderClass.isAssignableFrom(Builder.class)) {
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
