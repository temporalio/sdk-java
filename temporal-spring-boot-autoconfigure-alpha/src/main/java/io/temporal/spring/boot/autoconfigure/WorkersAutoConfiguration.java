/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.spring.boot.autoconfigure;

import io.temporal.client.WorkflowClient;
import io.temporal.common.metadata.POJOWorkflowImplMetadata;
import io.temporal.common.metadata.POJOWorkflowInterfaceMetadata;
import io.temporal.common.metadata.POJOWorkflowMethodMetadata;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.spring.boot.autoconfigure.properties.TemporalProperties;
import io.temporal.spring.boot.autoconfigure.properties.WorkerProperties;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import java.util.*;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionValidationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.*;
import org.springframework.context.event.ContextRefreshedEvent;

/** Provides a client based on `spring.temporal.workers` section */
@Configuration
@EnableConfigurationProperties(TemporalProperties.class)
@ConditionalOnClass(name = "io.temporal.worker.WorkerFactory")
@Conditional(WorkersPresentCondition.class)
@Import({WorkersAutoConfiguration.ActivityExplicitConfigProcessor.class})
public class WorkersAutoConfiguration {
  private static final Logger log = LoggerFactory.getLogger(WorkersAutoConfiguration.class);

  private final TemporalProperties properties;
  private final ConfigurableListableBeanFactory beanFactory;

  public WorkersAutoConfiguration(
      TemporalProperties properties, ConfigurableListableBeanFactory beanFactory) {
    this.properties = properties;
    this.beanFactory = beanFactory;
  }

  @Bean(name = "temporalWorkerFactory", destroyMethod = "shutdown")
  public WorkerFactory workerFactory(
      WorkflowClient workflowClient,
      @Qualifier("temporalTestWorkflowEnvironment") @Autowired(required = false) @Nullable
          TestWorkflowEnvironment testWorkflowEnvironment) {
    if (testWorkflowEnvironment != null) {
      return testWorkflowEnvironment.getWorkerFactory();
    } else {
      return WorkerFactory.newInstance(workflowClient);
    }
  }

  @Bean(name = "temporalWorkers")
  public Collection<Worker> workers(
      WorkerFactory workerFactory,
      @Qualifier("autoDiscoveredWorkflowImplementations") @Autowired(required = false) @Nullable
          Collection<Class<?>> autoDiscoveredWorkflowImplementationClasses) {
    Set<Worker> workers =
        properties.getWorkers() != null
            ? properties.getWorkers().stream()
                .map(
                    workerProperties ->
                        createExplicitWorkerWithProperties(workerFactory, workerProperties))
                .collect(Collectors.toSet())
            : new HashSet<>();

    if (autoDiscoveredWorkflowImplementationClasses != null) {
      for (Class<?> clazz : autoDiscoveredWorkflowImplementationClasses) {
        WorkflowImpl annotation = clazz.getAnnotation(WorkflowImpl.class);
        for (String taskQueue : annotation.taskQueues()) {
          Worker worker = workerFactory.tryGetWorker(taskQueue);
          if (worker == null) {
            worker = workerFactory.newWorker(taskQueue);
            log.info(
                "Creating auto-discovered worker with default settings for a task queue {} caused by a workflow class {}",
                taskQueue,
                clazz);
          }

          log.info(
              "Registering auto-discovered workflow class {} on a task queue {}", clazz, taskQueue);
          configureWorkflowImplementation(worker, clazz);
          workers.add(worker);
        }
      }
    }

    workers.forEach(
        worker -> beanFactory.registerSingleton("temporalWorker-" + worker.getTaskQueue(), worker));

    return workers;
  }

  @ConditionalOnProperty(prefix = "spring.temporal", name = "startWorkers", matchIfMissing = true)
  @Bean
  public WorkerFactoryStarter workerFactoryStarter(WorkerFactory workerFactory) {
    return new WorkerFactoryStarter(workerFactory);
  }

  Worker createExplicitWorkerWithProperties(
      WorkerFactory workerFactory, WorkerProperties workerProperties) {
    String taskQueue = workerProperties.getTaskQueue();
    if (workerFactory.tryGetWorker(taskQueue) != null) {
      throw new BeanDefinitionValidationException(
          "Worker for the task queue " + taskQueue + " already exists");
    }
    log.info("Creating configured worker for a task queue {}", taskQueue);
    Worker worker = workerFactory.newWorker(taskQueue);
    Collection<Class<?>> workflowClasses = workerProperties.getWorkflowClasses();
    if (workflowClasses != null) {
      workflowClasses.forEach(
          clazz -> {
            log.info(
                "Registering configured workflow class {} on a task queue {}", clazz, taskQueue);
            configureWorkflowImplementation(worker, clazz);
          });
    }

    return worker;
  }

  @SuppressWarnings("unchecked")
  private <T> void configureWorkflowImplementation(Worker worker, Class<?> clazz) {
    // TODO this code is a copy-past of a portion of
    // POJOWorkflowImplementationFactory#registerWorkflowImplementationType
    //  we should refactor it to separate the logic into reusable methods
    boolean hasWorkflowMethod = false;
    POJOWorkflowImplMetadata workflowMetadata = POJOWorkflowImplMetadata.newInstance(clazz);
    for (POJOWorkflowInterfaceMetadata workflowInterface :
        workflowMetadata.getWorkflowInterfaces()) {
      Optional<POJOWorkflowMethodMetadata> workflowMethod = workflowInterface.getWorkflowMethod();
      if (workflowMethod.isPresent()) {
        // TODO this is ugly. POJOWorkflowMethodMetadata needs to be generified
        worker.addWorkflowImplementationFactory(
            (Class<T>) workflowInterface.getInterfaceClass(),
            () -> (T) beanFactory.createBean(clazz));
        hasWorkflowMethod = true;
        break;
      }
    }

    if (!hasWorkflowMethod) {
      throw new BeanDefinitionValidationException(clazz + " doesn't have workflowMethod");
    }
  }

  public static class WorkerFactoryStarter implements ApplicationListener<ContextRefreshedEvent> {
    private final WorkerFactory workerFactory;

    public WorkerFactoryStarter(WorkerFactory workerFactory) {
      this.workerFactory = workerFactory;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
      workerFactory.start();
    }
  }

  public static class ActivityExplicitConfigProcessor implements BeanPostProcessor {
    private final ObjectFactory<TemporalProperties> propertiesProvider;
    private final ObjectFactory<WorkerFactory> workerFactoryProvider;
    private Map<String, List<String>> activityBeanToTaskQueues;

    // By using ObjectFactory we avoid eager initialization of the properties and workerFactory
    // which leads to a ton of warnings like
    // "PostProcessorRegistrationDelegate$BeanPostProcessorChecker - Bean
    // 'spring.temporal-io.temporal.spring.boot.autoconfigure.properties.TemporalProperties' of type
    // [io.temporal.spring.boot.autoconfigure.properties.TemporalProperties] is not eligible for
    // getting processed by all BeanPostProcessors (for example: not eligible for auto-proxying)"
    // because BeanPostProcessors are created before other beans and eager dependency on other beans
    // messes with an initialization ordering.
    public ActivityExplicitConfigProcessor(
        ObjectFactory<TemporalProperties> propertiesProvider,
        ObjectFactory<WorkerFactory> workerFactoryProvider) {
      this.propertiesProvider = propertiesProvider;
      this.workerFactoryProvider = workerFactoryProvider;
    }

    @Override
    public Object postProcessAfterInitialization(@Nonnull Object bean, @Nonnull String beanName)
        throws BeansException {
      if (activityBeanToTaskQueues == null) {
        // don't move it to init method, PostConstruct, constructor or any other steps that happen
        // before BeanPostProcessor#postProcessAfterInitialization
        // Otherwise you will get a nasty warning in console
        // "PostProcessorRegistrationDelegate$BeanPostProcessorChecker - Bean
        // 'spring.temporal-io.temporal.spring.boot.autoconfigure.properties.TemporalProperties' of
        // type [io.temporal.spring.boot.autoconfigure.properties.TemporalProperties] is not
        // eligible for getting processed by all BeanPostProcessors (for example: not eligible for
        // auto-proxying)"
        // because it will lead to an early usage of TemporalProperties before BeanPostProcessor
        // could be called on it.
        // For more context of the warning:
        // https://www.baeldung.com/spring-not-eligible-for-auto-proxying
        parseConfig();
      }

      if (activityBeanToTaskQueues.containsKey(beanName)) {
        activityBeanToTaskQueues.get(beanName).stream()
            .map(workerFactoryProvider.getObject()::newWorker)
            .forEach(
                worker -> {
                  log.info(
                      "Registering configured activity bean '{}' class {} on task queue {}",
                      beanName,
                      AopUtils.getTargetClass(bean),
                      worker.getTaskQueue());
                  worker.registerActivitiesImplementations(bean);
                });
      }
      return bean;
    }

    private void parseConfig() {
      activityBeanToTaskQueues = new HashMap<>();
      for (WorkerProperties workerProperties : propertiesProvider.getObject().getWorkers()) {
        Collection<String> activityBeans = workerProperties.getActivityBeans();
        if (activityBeans != null) {
          activityBeans.forEach(
              activityBeanName ->
                  activityBeanToTaskQueues
                      .computeIfAbsent(activityBeanName, k -> new ArrayList<>())
                      .add(workerProperties.getTaskQueue()));
        }
      }
    }
  }
}
