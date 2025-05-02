package io.temporal.spring.boot.autoconfigure;

import com.uber.m3.tally.Scope;
import io.opentracing.Tracer;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.schedules.ScheduleClientOptions;
import io.temporal.common.converter.DataConverter;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.spring.boot.autoconfigure.properties.TemporalProperties;
import io.temporal.spring.boot.autoconfigure.template.TestWorkflowEnvironmentAdapter;
import io.temporal.spring.boot.autoconfigure.template.WorkerFactoryOptionsTemplate;
import io.temporal.spring.boot.autoconfigure.template.WorkflowClientOptionsTemplate;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.WorkerFactoryOptions;
import java.util.Map;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
          TemporalOptionsCustomizer<TestEnvironmentOptions.Builder> testEnvOptionsCustomizer,
      @Autowired(required = false) @Nullable
          Map<String, TemporalOptionsCustomizer<WorkerFactoryOptions.Builder>>
              workerFactoryCustomizerMap,
      @Autowired(required = false) @Nullable
          Map<String, TemporalOptionsCustomizer<WorkflowClientOptions.Builder>> clientCustomizerMap,
      @Autowired(required = false) @Nullable
          Map<String, TemporalOptionsCustomizer<ScheduleClientOptions.Builder>>
              scheduleCustomizerMap) {
    DataConverter chosenDataConverter =
        AutoConfigurationUtils.choseDataConverter(dataConverters, mainDataConverter, properties);

    TemporalOptionsCustomizer<WorkerFactoryOptions.Builder> workerFactoryCustomizer =
        AutoConfigurationUtils.chooseTemporalCustomizerBean(
            workerFactoryCustomizerMap, WorkerFactoryOptions.Builder.class, properties);
    TemporalOptionsCustomizer<WorkflowClientOptions.Builder> clientCustomizer =
        AutoConfigurationUtils.chooseTemporalCustomizerBean(
            clientCustomizerMap, WorkflowClientOptions.Builder.class, properties);
    TemporalOptionsCustomizer<ScheduleClientOptions.Builder> scheduleCustomizer =
        AutoConfigurationUtils.chooseTemporalCustomizerBean(
            scheduleCustomizerMap, ScheduleClientOptions.Builder.class, properties);

    TestEnvironmentOptions.Builder options =
        TestEnvironmentOptions.newBuilder()
            .setWorkflowClientOptions(
                new WorkflowClientOptionsTemplate(
                        properties.getNamespace(),
                        chosenDataConverter,
                        otTracer,
                        clientCustomizer,
                        scheduleCustomizer)
                    .createWorkflowClientOptions());

    if (metricsScope != null) {
      options.setMetricsScope(metricsScope);
    }

    options.setWorkerFactoryOptions(
        new WorkerFactoryOptionsTemplate(properties, otTracer, workerFactoryCustomizer)
            .createWorkerFactoryOptions());

    if (testEnvOptionsCustomizer != null) {
      options = testEnvOptionsCustomizer.customize(options);
    }

    return TestWorkflowEnvironment.newInstance(options.build());
  }
}
