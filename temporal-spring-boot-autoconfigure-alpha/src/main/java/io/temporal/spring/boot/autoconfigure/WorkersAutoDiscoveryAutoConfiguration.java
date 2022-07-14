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

import io.temporal.spring.boot.ActivityImpl;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.spring.boot.autoconfigure.properties.TemporalProperties;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import java.util.*;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionValidationException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/**
 * Works only if `spring.temporal.workersAutoDiscovery` is true and responsible for auto-discovery
 * of workflow and activity implementations that are annotated with {@link
 * io.temporal.spring.boot.ActivityImpl} and {@link io.temporal.spring.boot.WorkflowImpl}
 */
@Configuration
@EnableConfigurationProperties(TemporalProperties.class)
@Import({WorkersAutoDiscoveryAutoConfiguration.ActivityAutoDiscoveryProcessor.class})
@Conditional(WorkersAutoDiscoveryPackagesPresentCondition.class)
public class WorkersAutoDiscoveryAutoConfiguration {
  private static final Logger log =
      LoggerFactory.getLogger(WorkersAutoDiscoveryAutoConfiguration.class);

  private final TemporalProperties properties;

  public WorkersAutoDiscoveryAutoConfiguration(TemporalProperties properties) {
    this.properties = properties;
  }

  @Bean(name = "autoDiscoveredWorkflowImplementations")
  public Collection<Class<?>> autoDiscoveredWorkflowImplementations() {
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

  /**
   * Activities to be registered on a worker need to be already instantiated. This is the reason
   * auto-discovery of activities has to be done in a post processor, when all bean definitions are
   * loaded and instances are created by Spring.
   */
  public static class ActivityAutoDiscoveryProcessor implements BeanPostProcessor {
    private final ObjectFactory<WorkerFactory> workerFactoryProvider;

    public ActivityAutoDiscoveryProcessor(ObjectFactory<WorkerFactory> workerFactoryProvider) {
      this.workerFactoryProvider = workerFactoryProvider;
    }

    @Override
    public Object postProcessAfterInitialization(@Nonnull Object bean, @Nonnull String beanName)
        throws BeansException {
      Class<?> targetClass = AopUtils.getTargetClass(bean);
      ActivityImpl activityAnnotation =
          AnnotationUtils.findAnnotation(targetClass, ActivityImpl.class);
      if (activityAnnotation != null) {
        for (String taskQueue : activityAnnotation.taskQueues()) {
          log.info(
              "Registering auto-discovered activity bean '{}' of class {} on task queue {}",
              beanName,
              targetClass,
              taskQueue);
          Worker worker = workerFactoryProvider.getObject().newWorker(taskQueue);
          worker.registerActivitiesImplementations(bean);
        }
      }
      return bean;
    }
  }
}
