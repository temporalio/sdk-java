package io.temporal.spring.boot.autoconfigure.template;

import io.temporal.common.WorkerDeploymentVersion;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.spring.boot.WorkerOptionsCustomizer;
import io.temporal.spring.boot.autoconfigure.properties.WorkerProperties;
import io.temporal.worker.WorkerDeploymentOptions;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.tuning.PollerBehaviorAutoscaling;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

class WorkerOptionsTemplate {
  private final @Nonnull String taskQueue;
  private final @Nonnull String workerName;
  private final @Nullable WorkerProperties workerProperties;
  private final @Nullable List<TemporalOptionsCustomizer<WorkerOptions.Builder>> customizers;

  WorkerOptionsTemplate(
      @Nonnull String workerName,
      @Nonnull String taskQueue,
      @Nullable WorkerProperties workerProperties,
      @Nullable List<TemporalOptionsCustomizer<WorkerOptions.Builder>> customizers) {
    this.workerName = workerName;
    this.taskQueue = taskQueue;
    this.workerProperties = workerProperties;
    this.customizers = customizers;
  }

  @SuppressWarnings("deprecation")
  WorkerOptions createWorkerOptions() {
    WorkerOptions.Builder options = WorkerOptions.newBuilder();

    if (workerProperties != null) {
      WorkerProperties.CapacityConfigurationProperties threadsConfiguration =
          workerProperties.getCapacity();
      if (threadsConfiguration != null) {
        Optional.ofNullable(threadsConfiguration.getMaxConcurrentWorkflowTaskExecutors())
            .ifPresent(options::setMaxConcurrentWorkflowTaskExecutionSize);
        Optional.ofNullable(threadsConfiguration.getMaxConcurrentActivityExecutors())
            .ifPresent(options::setMaxConcurrentActivityExecutionSize);
        Optional.ofNullable(threadsConfiguration.getMaxConcurrentLocalActivityExecutors())
            .ifPresent(options::setMaxConcurrentLocalActivityExecutionSize);
        Optional.ofNullable(threadsConfiguration.getMaxConcurrentNexusTasksExecutors())
            .ifPresent(options::setMaxConcurrentNexusExecutionSize);
        Optional.ofNullable(threadsConfiguration.getMaxConcurrentWorkflowTaskPollers())
            .ifPresent(options::setMaxConcurrentWorkflowTaskPollers);
        Optional.ofNullable(threadsConfiguration.getMaxConcurrentActivityTaskPollers())
            .ifPresent(options::setMaxConcurrentActivityTaskPollers);
        Optional.ofNullable(threadsConfiguration.getMaxConcurrentNexusTaskPollers())
            .ifPresent(options::setMaxConcurrentNexusTaskPollers);
        if (threadsConfiguration.getWorkflowTaskPollersConfiguration() != null) {
          WorkerProperties.PollerConfigurationProperties.PollerBehaviorAutoscalingConfiguration
              pollerBehaviorAutoscaling =
                  threadsConfiguration
                      .getWorkflowTaskPollersConfiguration()
                      .getPollerBehaviorAutoscaling();
          if (pollerBehaviorAutoscaling != null && pollerBehaviorAutoscaling.isEnabled()) {
            options.setWorkflowTaskPollersBehavior(
                new PollerBehaviorAutoscaling(
                    pollerBehaviorAutoscaling.getMinConcurrentTaskPollers(),
                    pollerBehaviorAutoscaling.getMaxConcurrentTaskPollers(),
                    pollerBehaviorAutoscaling.getInitialConcurrentTaskPollers()));
          }
        }
        if (threadsConfiguration.getActivityTaskPollersConfiguration() != null) {
          WorkerProperties.PollerConfigurationProperties.PollerBehaviorAutoscalingConfiguration
              pollerBehaviorAutoscaling =
                  threadsConfiguration
                      .getActivityTaskPollersConfiguration()
                      .getPollerBehaviorAutoscaling();
          if (pollerBehaviorAutoscaling != null && pollerBehaviorAutoscaling.isEnabled()) {
            options.setActivityTaskPollersBehavior(
                new PollerBehaviorAutoscaling(
                    pollerBehaviorAutoscaling.getMinConcurrentTaskPollers(),
                    pollerBehaviorAutoscaling.getMaxConcurrentTaskPollers(),
                    pollerBehaviorAutoscaling.getInitialConcurrentTaskPollers()));
          }
        }
        if (threadsConfiguration.getNexusTaskPollersConfiguration() != null) {
          WorkerProperties.PollerConfigurationProperties.PollerBehaviorAutoscalingConfiguration
              pollerBehaviorAutoscaling =
                  threadsConfiguration
                      .getNexusTaskPollersConfiguration()
                      .getPollerBehaviorAutoscaling();
          if (pollerBehaviorAutoscaling != null && pollerBehaviorAutoscaling.isEnabled()) {
            options.setNexusTaskPollersBehavior(
                new PollerBehaviorAutoscaling(
                    pollerBehaviorAutoscaling.getMinConcurrentTaskPollers(),
                    pollerBehaviorAutoscaling.getMaxConcurrentTaskPollers(),
                    pollerBehaviorAutoscaling.getInitialConcurrentTaskPollers()));
          }
        }
      }

      WorkerProperties.RateLimitsConfigurationProperties rateLimitConfiguration =
          workerProperties.getRateLimits();
      if (rateLimitConfiguration != null) {
        Optional.ofNullable(rateLimitConfiguration.getMaxWorkerActivitiesPerSecond())
            .ifPresent(options::setMaxWorkerActivitiesPerSecond);
        Optional.ofNullable(rateLimitConfiguration.getMaxTaskQueueActivitiesPerSecond())
            .ifPresent(options::setMaxTaskQueueActivitiesPerSecond);
      }

      WorkerProperties.BuildIdConfigurationProperties buildIdConfigurations =
          workerProperties.getBuildId();
      if (buildIdConfigurations != null) {
        Optional.ofNullable(buildIdConfigurations.getWorkerBuildId())
            .ifPresent(options::setBuildId);
        options.setUseBuildIdForVersioning(buildIdConfigurations.getEnabledWorkerVersioning());
      }

      WorkerProperties.VirtualThreadConfigurationProperties virtualThreadConfiguration =
          workerProperties.getVirtualThreads();
      if (virtualThreadConfiguration != null) {
        Optional.ofNullable(virtualThreadConfiguration.isUsingVirtualThreads())
            .ifPresent(options::setUsingVirtualThreads);
        Optional.ofNullable(virtualThreadConfiguration.isUsingVirtualThreadsOnWorkflowWorker())
            .ifPresent(options::setUsingVirtualThreadsOnWorkflowWorker);
        Optional.ofNullable(virtualThreadConfiguration.isUsingVirtualThreadsOnActivityWorker())
            .ifPresent(options::setUsingVirtualThreadsOnActivityWorker);
        Optional.ofNullable(virtualThreadConfiguration.isUsingVirtualThreadsOnLocalActivityWorker())
            .ifPresent(options::setUsingVirtualThreadsOnLocalActivityWorker);
        Optional.ofNullable(virtualThreadConfiguration.isUsingVirtualThreadsOnNexusWorker())
            .ifPresent(options::setUsingVirtualThreadsOnNexusWorker);
      }
      WorkerProperties.WorkerDeploymentConfigurationProperties workerDeploymentConfiguration =
          workerProperties.getDeploymentProperties();
      if (workerDeploymentConfiguration != null) {
        WorkerDeploymentOptions.Builder opts = WorkerDeploymentOptions.newBuilder();
        Optional.ofNullable(workerDeploymentConfiguration.getUseVersioning())
            .ifPresent(opts::setUseVersioning);
        Optional.ofNullable(workerDeploymentConfiguration.getDefaultVersioningBehavior())
            .ifPresent(opts::setDefaultVersioningBehavior);

        if (workerDeploymentConfiguration.getDeploymentName() != null
            || workerDeploymentConfiguration.getBuildId() != null) {
          if (workerDeploymentConfiguration.getBuildId() == null
              || workerDeploymentConfiguration.getDeploymentName() == null) {
            throw new IllegalArgumentException(
                "deploymentName and buildId must both be set when either is specified");
          }
          if (workerDeploymentConfiguration.getDeploymentVersion() != null) {
            throw new IllegalArgumentException(
                "deploymentVersion is exclusive with deploymentName and buildId");
          }
          opts.setVersion(
              new WorkerDeploymentVersion(
                  workerDeploymentConfiguration.getDeploymentName(),
                  workerDeploymentConfiguration.getBuildId()));
        } else {
          Optional.ofNullable(workerDeploymentConfiguration.getDeploymentVersion())
              .ifPresent((v) -> opts.setVersion(WorkerDeploymentVersion.fromCanonicalString(v)));
        }
        options.setDeploymentOptions(opts.build());
      }
    }

    if (customizers != null) {
      for (TemporalOptionsCustomizer<WorkerOptions.Builder> customizer : customizers) {
        options = customizer.customize(options);
        if (customizer instanceof WorkerOptionsCustomizer) {
          options =
              ((WorkerOptionsCustomizer) customizer).customize(options, workerName, taskQueue);
        }
      }
    }
    return options.build();
  }
}
