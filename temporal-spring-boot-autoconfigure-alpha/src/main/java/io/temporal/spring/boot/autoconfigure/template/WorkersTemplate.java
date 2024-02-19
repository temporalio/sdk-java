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

package io.temporal.spring.boot.autoconfigure.template;

import com.google.common.base.Preconditions;
import io.opentracing.Tracer;
import io.temporal.client.WorkflowClient;
import io.temporal.common.Experimental;
import io.temporal.common.metadata.POJOWorkflowImplMetadata;
import io.temporal.common.metadata.POJOWorkflowMethodMetadata;
import io.temporal.spring.boot.ActivityImpl;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.spring.boot.autoconfigure.properties.NamespaceProperties;
import io.temporal.spring.boot.autoconfigure.properties.TemporalProperties;
import io.temporal.spring.boot.autoconfigure.properties.WorkerProperties;
import io.temporal.worker.*;
import java.util.*;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionValidationException;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.Environment;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.Assert;

/** Creates a {@link WorkerFactory} and Workers for a given namespace provided by WorkflowClient. */
public class WorkersTemplate implements BeanFactoryAware, EnvironmentAware {
  private static final Logger log = LoggerFactory.getLogger(WorkersTemplate.class);

  private final @Nonnull TemporalProperties properties;
  private final @Nonnull NamespaceProperties namespaceProperties;
  private final ClientTemplate clientTemplate;
  private final @Nullable Tracer tracer;
  // if not null, we work with an environment with defined test server
  private final @Nullable TestWorkflowEnvironmentAdapter testWorkflowEnvironment;

  private final @Nullable TemporalOptionsCustomizer<WorkerFactoryOptions.Builder>
      workerFactoryCustomizer;

  private final @Nullable TemporalOptionsCustomizer<WorkerOptions.Builder> workerCustomizer;
  private final @Nullable TemporalOptionsCustomizer<WorkflowImplementationOptions.Builder>
      workflowImplementationCustomizer;

  private ConfigurableListableBeanFactory beanFactory;
  private Environment environment;

  private WorkerFactory workerFactory;
  private Collection<Worker> workers;
  private final Map<String, RegisteredInfo> registeredInfo = new HashMap<>();

  public WorkersTemplate(
      @Nonnull TemporalProperties properties,
      @Nonnull NamespaceProperties namespaceProperties,
      @Nullable ClientTemplate clientTemplate,
      @Nullable Tracer tracer,
      @Nullable TestWorkflowEnvironmentAdapter testWorkflowEnvironment,
      @Nullable TemporalOptionsCustomizer<WorkerFactoryOptions.Builder> workerFactoryCustomizer,
      @Nullable TemporalOptionsCustomizer<WorkerOptions.Builder> workerCustomizer,
      @Nullable
          TemporalOptionsCustomizer<WorkflowImplementationOptions.Builder>
              workflowImplementationCustomizer) {
    this.properties = properties;
    this.namespaceProperties = namespaceProperties;
    this.tracer = tracer;
    this.testWorkflowEnvironment = testWorkflowEnvironment;
    this.clientTemplate = clientTemplate;

    this.workerFactoryCustomizer = workerFactoryCustomizer;
    this.workerCustomizer = workerCustomizer;
    this.workflowImplementationCustomizer = workflowImplementationCustomizer;
  }

  public WorkerFactory getWorkerFactory() {
    if (workerFactory == null) {
      this.workerFactory = createWorkerFactory(clientTemplate.getWorkflowClient());
    }
    return workerFactory;
  }

  public Collection<Worker> getWorkers() {
    if (workers == null) {
      this.workers = createWorkers(getWorkerFactory());
    }
    return workers;
  }

  /** Return information on registered workflow and activity types per task queue */
  @Experimental
  public Map<String, RegisteredInfo> getRegisteredInfo() {
    if (workers == null) {
      this.workers = createWorkers(getWorkerFactory());
    }
    return registeredInfo;
  }

  WorkerFactory createWorkerFactory(WorkflowClient workflowClient) {
    if (testWorkflowEnvironment != null) {
      return testWorkflowEnvironment.getWorkerFactory();
    } else {
      WorkerFactoryOptions workerFactoryOptions =
          new WorkerFactoryOptionsTemplate(namespaceProperties, tracer, workerFactoryCustomizer)
              .createWorkerFactoryOptions();
      return WorkerFactory.newInstance(workflowClient, workerFactoryOptions);
    }
  }

  private Collection<Worker> createWorkers(WorkerFactory workerFactory) {
    Workers workers = new Workers();

    // explicitly configured workflow implementations
    if (properties.getWorkers() != null) {
      properties
          .getWorkers()
          .forEach(
              workerProperties ->
                  createWorkerFromAnExplicitConfig(workerFactory, workerProperties, workers));
    }

    if (properties.getWorkersAutoDiscovery() != null
        && properties.getWorkersAutoDiscovery().getPackages() != null) {
      Collection<Class<?>> autoDiscoveredWorkflowImplementationClasses =
          autoDiscoverWorkflowImplementations();
      Map<String, Object> autoDiscoveredActivityBeans = autoDiscoverActivityBeans();

      configureWorkflowImplementationsByTaskQueue(
          workerFactory, workers, autoDiscoveredWorkflowImplementationClasses);
      configureActivityBeansByTaskQueue(workerFactory, workers, autoDiscoveredActivityBeans);
      configureWorkflowImplementationsByWorkerName(
          workers, autoDiscoveredWorkflowImplementationClasses);
      configureActivityBeansByWorkerName(workers, autoDiscoveredActivityBeans);
    }

    return workers.getWorkers();
  }

  private void configureWorkflowImplementationsByTaskQueue(
      WorkerFactory workerFactory,
      Workers workers,
      Collection<Class<?>> autoDiscoveredWorkflowImplementationClasses) {
    for (Class<?> clazz : autoDiscoveredWorkflowImplementationClasses) {
      WorkflowImpl annotation = clazz.getAnnotation(WorkflowImpl.class);
      for (String taskQueue : annotation.taskQueues()) {
        taskQueue = environment.resolvePlaceholders(taskQueue);
        Worker worker = workerFactory.tryGetWorker(taskQueue);
        if (worker == null) {
          log.info(
              "Creating a worker with default settings for a task queue '{}' "
                  + "caused by an auto-discovered workflow class {}",
              taskQueue,
              clazz);

          worker = createNewWorker(taskQueue, null, workers);
        }

        configureWorkflowImplementationAutoDiscovery(worker, clazz, null, workers);
      }
    }
  }

  private void configureActivityBeansByTaskQueue(
      WorkerFactory workerFactory,
      Workers workers,
      Map<String, Object> autoDiscoveredActivityBeans) {
    autoDiscoveredActivityBeans.forEach(
        (beanName, bean) -> {
          Class<?> targetClass = AopUtils.getTargetClass(bean);
          ActivityImpl annotation = AnnotationUtils.findAnnotation(targetClass, ActivityImpl.class);
          if (annotation != null) {
            for (String taskQueue : annotation.taskQueues()) {
              taskQueue = environment.resolvePlaceholders(taskQueue);
              Worker worker = workerFactory.tryGetWorker(taskQueue);
              if (worker == null) {
                log.info(
                    "Creating a worker with default settings for a task queue '{}' "
                        + "caused by an auto-discovered activity class {}",
                    taskQueue,
                    targetClass);
                worker = createNewWorker(taskQueue, null, workers);
              }

              configureActivityImplementationAutoDiscovery(
                  worker, bean, beanName, targetClass, null, workers);
            }
          }
        });
  }

  private void configureWorkflowImplementationsByWorkerName(
      Workers workers, Collection<Class<?>> autoDiscoveredWorkflowImplementationClasses) {
    for (Class<?> clazz : autoDiscoveredWorkflowImplementationClasses) {
      WorkflowImpl annotation = clazz.getAnnotation(WorkflowImpl.class);

      for (String workerName : annotation.workers()) {
        Worker worker = workers.getByName(workerName);
        if (worker == null) {
          throw new BeanDefinitionValidationException(
              "Worker with name "
                  + workerName
                  + " is not found in the config, but is referenced by auto-discovered workflow implementation class "
                  + clazz);
        }

        configureWorkflowImplementationAutoDiscovery(worker, clazz, workerName, workers);
      }
    }
  }

  private void configureActivityBeansByWorkerName(
      Workers workers, Map<String, Object> autoDiscoveredActivityBeans) {
    autoDiscoveredActivityBeans.forEach(
        (beanName, bean) -> {
          Class<?> targetClass = AopUtils.getTargetClass(bean);
          ActivityImpl annotation = AnnotationUtils.findAnnotation(targetClass, ActivityImpl.class);
          if (annotation != null) {
            for (String workerName : annotation.workers()) {
              Worker worker = workers.getByName(workerName);
              if (worker == null) {
                throw new BeanDefinitionValidationException(
                    "Worker with name "
                        + workerName
                        + " is not found in the config, but is referenced by auto-discovered activity bean "
                        + beanName);
              }

              configureActivityImplementationAutoDiscovery(
                  worker, bean, beanName, targetClass, workerName, workers);
            }
          }
        });
  }

  private Collection<Class<?>> autoDiscoverWorkflowImplementations() {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(WorkflowImpl.class));
    Set<Class<?>> implementations = new HashSet<>();
    for (String pckg : properties.getWorkersAutoDiscovery().getPackages()) {
      Set<BeanDefinition> candidateComponents = scanner.findCandidateComponents(pckg);
      for (BeanDefinition beanDefinition : candidateComponents) {
        try {
          implementations.add(Class.forName(beanDefinition.getBeanClassName()));
        } catch (ClassNotFoundException e) {
          throw new BeanDefinitionValidationException(
              "Fail loading class for bean definition " + beanDefinition, e);
        }
      }
    }
    return implementations;
  }

  private Map<String, Object> autoDiscoverActivityBeans() {
    return beanFactory.getBeansWithAnnotation(ActivityImpl.class);
  }

  private void createWorkerFromAnExplicitConfig(
      WorkerFactory workerFactory, WorkerProperties workerProperties, Workers workers) {
    String taskQueue = workerProperties.getTaskQueue();
    if (workerFactory.tryGetWorker(taskQueue) != null) {
      throw new BeanDefinitionValidationException(
          "Worker for the task queue "
              + taskQueue
              + " already exists. Duplicate workers in the config?");
    }
    log.info("Creating configured worker for a task queue {}", taskQueue);
    Worker worker = createNewWorker(taskQueue, workerProperties, workers);

    Collection<Class<?>> workflowClasses = workerProperties.getWorkflowClasses();
    if (workflowClasses != null) {
      workflowClasses.forEach(
          clazz -> {
            log.info(
                "Registering configured workflow class {} on a task queue '{}'", clazz, taskQueue);
            configureWorkflowImplementation(worker, clazz);
          });
    }

    Collection<String> activityBeans = workerProperties.getActivityBeans();
    if (activityBeans != null) {
      activityBeans.forEach(
          beanName -> {
            Object bean = beanFactory.getBean(beanName);
            log.info(
                "Registering configured activity bean '{}' of a {} class on task queue '{}'",
                beanName,
                AopUtils.getTargetClass(bean),
                taskQueue);
            worker.registerActivitiesImplementations(bean);
            addRegisteredActivityImpl(worker, beanName, bean.getClass().getName());
          });
    }
  }

  private void configureActivityImplementationAutoDiscovery(
      Worker worker,
      Object bean,
      String beanName,
      Class<?> targetClass,
      String byWorkerName,
      Workers workers) {
    try {
      worker.registerActivitiesImplementations(bean);
      addRegisteredActivityImpl(worker, beanName, bean.getClass().getName());
      if (log.isInfoEnabled()) {
        log.info(
            "Registering auto-discovered activity bean '{}' of class {} on a worker {}with a task queue '{}'",
            beanName,
            targetClass,
            byWorkerName != null ? "'" + byWorkerName + "' " : "",
            worker.getTaskQueue());
      }
    } catch (TypeAlreadyRegisteredException registeredEx) {
      if (log.isInfoEnabled()) {
        log.info(
            "Skipping auto-discovered activity bean '{}' of class {} on a worker {}with a task queue '{}'"
                + " as activity type '{}' is already registered on the worker",
            beanName,
            targetClass,
            byWorkerName != null ? "'" + byWorkerName + "' " : "",
            worker.getTaskQueue(),
            registeredEx.getRegisteredTypeName());
      }
    }
  }

  private void configureWorkflowImplementationAutoDiscovery(
      Worker worker, Class<?> clazz, String byWorkerName, Workers workers) {
    try {
      configureWorkflowImplementation(worker, clazz);
      if (log.isInfoEnabled()) {
        log.info(
            "Registering auto-discovered workflow class {} on a worker {}with a task queue '{}'",
            clazz,
            byWorkerName != null ? "'" + byWorkerName + "' " : "",
            worker.getTaskQueue());
      }
    } catch (TypeAlreadyRegisteredException registeredEx) {
      if (log.isInfoEnabled()) {
        log.info(
            "Skip registering of auto-discovered workflow class {} on a worker {}with a task queue '{}' "
                + "as workflow type '{}' is already registered on the worker",
            clazz,
            byWorkerName != null ? "'" + byWorkerName + "' " : "",
            worker.getTaskQueue(),
            registeredEx.getRegisteredTypeName());
      }
    }
  }

  @SuppressWarnings("unchecked")
  private <T> void configureWorkflowImplementation(Worker worker, Class<?> clazz) {

    POJOWorkflowImplMetadata workflowMetadata = POJOWorkflowImplMetadata.newInstance(clazz);
    List<POJOWorkflowMethodMetadata> workflowMethods = workflowMetadata.getWorkflowMethods();
    if (workflowMethods.isEmpty()) {
      throw new BeanDefinitionValidationException(
          "Workflow implementation doesn't implement any interface "
              + "with a workflow method annotated with @WorkflowMethod: "
              + clazz);
    }

    WorkflowImplementationOptions workflowImplementationOptions =
        new WorkflowImplementationOptionsTemplate(workflowImplementationCustomizer)
            .createWorkflowImplementationOptions();

    for (POJOWorkflowMethodMetadata workflowMethod : workflowMetadata.getWorkflowMethods()) {
      worker.registerWorkflowImplementationFactory(
          (Class<T>) workflowMethod.getWorkflowInterface(),
          () -> (T) beanFactory.createBean(clazz),
          workflowImplementationOptions);
      addRegisteredWorkflowImpl(worker, workflowMethod.getWorkflowInterface().getName());
    }
  }

  @Override
  public void setBeanFactory(@Nonnull BeanFactory beanFactory) throws BeansException {
    Assert.isInstanceOf(ConfigurableListableBeanFactory.class, beanFactory);
    this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
  }

  @Override
  public void setEnvironment(@Nonnull Environment environment) {
    this.environment = environment;
  }

  private Worker createNewWorker(
      @Nonnull String taskQueue, @Nullable WorkerProperties properties, @Nonnull Workers workers) {
    Preconditions.checkState(
        workerFactory.tryGetWorker(taskQueue) == null,
        "[BUG] This method should never be called twice for the same Task Queue='%s'",
        taskQueue);

    String workerName =
        properties != null && properties.getName() != null ? properties.getName() : taskQueue;

    WorkerOptions workerOptions =
        new WorkerOptionsTemplate(workerName, taskQueue, properties, workerCustomizer)
            .createWorkerOptions();
    Worker worker = workerFactory.newWorker(taskQueue, workerOptions);
    workers.addWorker(workerName, worker);
    return worker;
  }

  private void addRegisteredWorkflowImpl(Worker worker, String workflowClass) {
    if (!registeredInfo.containsKey(worker.getTaskQueue())) {
      registeredInfo.put(
          worker.getTaskQueue(),
          new RegisteredInfo()
              .addWorkflowInfo(new RegisteredWorkflowInfo().addClassName(workflowClass)));
    } else {
      registeredInfo
          .get(worker.getTaskQueue())
          .getRegisteredWorkflowInfo()
          .add(new RegisteredWorkflowInfo().addClassName(workflowClass));
    }
  }

  private void addRegisteredActivityImpl(Worker worker, String beanName, String beanClass) {
    if (!registeredInfo.containsKey(worker.getTaskQueue())) {
      registeredInfo.put(
          worker.getTaskQueue(),
          new RegisteredInfo()
              .addActivityInfo(
                  new RegisteredActivityInfo().addBeanName(beanName).addClassName(beanClass)));
    } else {
      registeredInfo
          .get(worker.getTaskQueue())
          .getRegisteredActivityInfo()
          .add(new RegisteredActivityInfo().addBeanName(beanName).addClassName(beanClass));
    }
  }

  public static class RegisteredInfo {
    private final List<RegisteredActivityInfo> registeredActivityInfo = new ArrayList<>();
    private final List<RegisteredWorkflowInfo> registeredWorkflowInfo = new ArrayList<>();

    public RegisteredInfo addActivityInfo(RegisteredActivityInfo activityInfo) {
      registeredActivityInfo.add(activityInfo);
      return this;
    }

    public RegisteredInfo addWorkflowInfo(RegisteredWorkflowInfo workflowInfo) {
      registeredWorkflowInfo.add(workflowInfo);
      return this;
    }

    public List<RegisteredActivityInfo> getRegisteredActivityInfo() {
      return registeredActivityInfo;
    }

    public List<RegisteredWorkflowInfo> getRegisteredWorkflowInfo() {
      return registeredWorkflowInfo;
    }
  }

  public static class RegisteredActivityInfo {
    private String beanName;
    private String className;

    public RegisteredActivityInfo addClassName(String className) {
      this.className = className;
      return this;
    }

    public RegisteredActivityInfo addBeanName(String beanName) {
      this.beanName = beanName;
      return this;
    }

    public String getClassName() {
      return className;
    }

    public String getBeanName() {
      return beanName;
    }
  }

  public static class RegisteredWorkflowInfo {
    private String className;

    public RegisteredWorkflowInfo addClassName(String className) {
      this.className = className;
      return this;
    }

    public String getClassName() {
      return className;
    }
  }

  private static class Workers {
    private final Map<String, Worker> workersByName = new HashMap<>();
    private final Map<String, Worker> workersByTaskQueue = new HashMap<>();
    private final List<Worker> workers = new ArrayList<>();

    public void addWorker(@Nonnull String workerName, Worker newWorker) {
      Worker existingWorker = workersByTaskQueue.get(newWorker.getTaskQueue());
      // Caller of this method should make sure that it doesn't try to register a worker for a
      // task queue that already has a worker.
      Preconditions.checkState(
          existingWorker == null,
          "[BUG] Worker with Task Queue='%s' already exists.",
          newWorker.getTaskQueue());

      existingWorker = workersByName.get(workerName);
      if (existingWorker != null) {
        throw new BeanDefinitionValidationException(
            "Worker name "
                + workerName
                + " is shared between Workers on different Task Queues '"
                + existingWorker.getTaskQueue()
                + "' and '"
                + newWorker.getTaskQueue()
                + "'. Worker names should be unique.");
      }

      workers.add(newWorker);
      workersByTaskQueue.put(newWorker.getTaskQueue(), newWorker);
      workersByName.put(workerName, newWorker);
    }

    public List<Worker> getWorkers() {
      return workers;
    }

    @Nullable
    public Worker getByName(String workerName) {
      return workersByName.get(workerName);
    }
  }
}
