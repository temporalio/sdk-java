package io.temporal.spring.boot.autoconfigure;

import com.uber.m3.tally.Scope;
import io.opentracing.Tracer;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowClientPlugin;
import io.temporal.client.schedules.ScheduleClientOptions;
import io.temporal.client.schedules.ScheduleClientPlugin;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.interceptors.ScheduleClientInterceptor;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.common.interceptors.WorkflowClientInterceptor;
import io.temporal.serviceclient.WorkflowServiceStubsPlugin;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.spring.boot.autoconfigure.properties.TemporalProperties;
import io.temporal.spring.boot.autoconfigure.template.TestWorkflowEnvironmentAdapter;
import io.temporal.spring.boot.autoconfigure.template.WorkerFactoryOptionsTemplate;
import io.temporal.spring.boot.autoconfigure.template.WorkflowClientOptionsTemplate;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerPlugin;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Provides a client based on `spring.temporal.testServer` section */
@Configuration
@EnableConfigurationProperties(TemporalProperties.class)
@ConditionalOnClass(name = "io.temporal.testing.TestWorkflowEnvironment")
@ConditionalOnProperty(
    prefix = "spring.temporal",
    name = "test-server.enabled",
    havingValue = "true")
@AutoConfigureAfter({OpenTracingAutoConfiguration.class, MetricsScopeAutoConfiguration.class})
public class TestServerAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(TestServerAutoConfiguration.class);

  private final ConfigurableListableBeanFactory beanFactory;

  public TestServerAutoConfiguration(ConfigurableListableBeanFactory beanFactory) {
    this.beanFactory = beanFactory;
  }

  @Bean(name = "temporalTestWorkflowEnvironmentAdapter")
  public TestWorkflowEnvironmentAdapter testTestWorkflowEnvironmentAdapter(
      @Qualifier("temporalTestWorkflowEnvironment")
          TestWorkflowEnvironment testWorkflowEnvironment) {
    return new TestWorkflowEnvironmentAdapterImpl(testWorkflowEnvironment);
  }

  @Bean(name = "temporalTestWorkflowEnvironment", destroyMethod = "close")
  public TestWorkflowEnvironment testWorkflowEnvironment(
      TemporalProperties properties,
      @Qualifier("temporalMetricsScope") @Autowired(required = false) @Nullable Scope metricsScope,
      @Autowired(required = false) Map<String, DataConverter> dataConverters,
      @Qualifier("mainDataConverter") @Autowired(required = false) @Nullable
          DataConverter mainDataConverter,
      @Autowired(required = false) @Nullable Tracer otTracer,
      @Autowired(required = false) @Nullable
          List<WorkflowClientInterceptor> workflowClientInterceptors,
      @Autowired(required = false) @Nullable
          List<ScheduleClientInterceptor> scheduleClientInterceptors,
      @Autowired(required = false) @Nullable List<WorkerInterceptor> workerInterceptors,
      @Autowired(required = false) @Nullable
          List<TemporalOptionsCustomizer<TestEnvironmentOptions.Builder>> testEnvOptionsCustomizers,
      @Autowired(required = false) @Nullable
          Map<String, TemporalOptionsCustomizer<WorkerFactoryOptions.Builder>>
              workerFactoryCustomizerMap,
      @Autowired(required = false) @Nullable
          Map<String, TemporalOptionsCustomizer<WorkflowClientOptions.Builder>> clientCustomizerMap,
      @Autowired(required = false) @Nullable
          Map<String, TemporalOptionsCustomizer<ScheduleClientOptions.Builder>>
              scheduleCustomizerMap,
      @Autowired(required = false) @Nullable List<WorkflowClientPlugin> workflowClientPlugins,
      @Autowired(required = false) @Nullable List<ScheduleClientPlugin> scheduleClientPlugins,
      @Autowired(required = false) @Nullable List<WorkerPlugin> workerPlugins) {
    DataConverter chosenDataConverter =
        AutoConfigurationUtils.chooseDataConverter(dataConverters, mainDataConverter, properties);
    List<WorkflowClientInterceptor> chosenClientInterceptors =
        AutoConfigurationUtils.chooseWorkflowClientInterceptors(
            workflowClientInterceptors, properties);
    List<ScheduleClientInterceptor> chosenScheduleClientInterceptors =
        AutoConfigurationUtils.chooseScheduleClientInterceptors(
            scheduleClientInterceptors, properties);
    List<WorkerInterceptor> chosenWorkerInterceptors =
        AutoConfigurationUtils.chooseWorkerInterceptors(workerInterceptors, properties);

    List<TemporalOptionsCustomizer<WorkerFactoryOptions.Builder>> workerFactoryCustomizer =
        AutoConfigurationUtils.chooseTemporalCustomizerBeans(
            beanFactory,
            workerFactoryCustomizerMap,
            WorkerFactoryOptions.Builder.class,
            properties);
    List<TemporalOptionsCustomizer<WorkflowClientOptions.Builder>> clientCustomizer =
        AutoConfigurationUtils.chooseTemporalCustomizerBeans(
            beanFactory, clientCustomizerMap, WorkflowClientOptions.Builder.class, properties);
    List<TemporalOptionsCustomizer<ScheduleClientOptions.Builder>> scheduleCustomizer =
        AutoConfigurationUtils.chooseTemporalCustomizerBeans(
            beanFactory, scheduleCustomizerMap, ScheduleClientOptions.Builder.class, properties);

    // Filter plugins so each is only registered at its highest applicable level.
    // Note: TestWorkflowEnvironment doesn't support WorkflowServiceStubsPlugin directly since it
    // creates its own test server. We filter those out and handle the rest.
    List<WorkflowClientPlugin> filteredClientPlugins =
        filterPlugins(workflowClientPlugins, WorkflowServiceStubsPlugin.class);
    List<ScheduleClientPlugin> filteredSchedulePlugins =
        filterPlugins(scheduleClientPlugins, WorkflowServiceStubsPlugin.class);
    List<WorkerPlugin> filteredWorkerPlugins =
        filterPlugins(
            filterPlugins(workerPlugins, WorkflowServiceStubsPlugin.class),
            WorkflowClientPlugin.class);

    TestEnvironmentOptions.Builder options =
        TestEnvironmentOptions.newBuilder()
            .setWorkflowClientOptions(
                new WorkflowClientOptionsTemplate(
                        properties.getNamespace(),
                        chosenDataConverter,
                        chosenClientInterceptors,
                        chosenScheduleClientInterceptors,
                        otTracer,
                        clientCustomizer,
                        scheduleCustomizer,
                        filteredClientPlugins,
                        filteredSchedulePlugins)
                    .createWorkflowClientOptions());

    if (metricsScope != null) {
      options.setMetricsScope(metricsScope);
    }

    options.setWorkerFactoryOptions(
        new WorkerFactoryOptionsTemplate(
                properties,
                chosenWorkerInterceptors,
                otTracer,
                workerFactoryCustomizer,
                filteredWorkerPlugins)
            .createWorkerFactoryOptions());

    if (testEnvOptionsCustomizers != null) {
      for (TemporalOptionsCustomizer<TestEnvironmentOptions.Builder> testEnvOptionsCustomizer :
          testEnvOptionsCustomizers) {
        options = testEnvOptionsCustomizer.customize(options);
      }
    }

    return TestWorkflowEnvironment.newInstance(options.build());
  }

  /**
   * Filter out plugins that implement a higher-level plugin interface, as those are handled at that
   * higher level via propagation.
   */
  private static <T> @Nullable List<T> filterPlugins(
      @Nullable List<T> plugins, Class<?> excludeType) {
    if (plugins == null || plugins.isEmpty()) {
      return plugins;
    }
    List<T> filtered = new ArrayList<>();
    for (T plugin : plugins) {
      if (!excludeType.isInstance(plugin)) {
        filtered.add(plugin);
      }
    }
    return filtered.isEmpty() ? null : filtered;
  }
}
