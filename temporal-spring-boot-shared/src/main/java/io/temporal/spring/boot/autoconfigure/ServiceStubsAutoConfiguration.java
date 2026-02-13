package io.temporal.spring.boot.autoconfigure;

import com.uber.m3.tally.Scope;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.serviceclient.WorkflowServiceStubsOptions.Builder;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.spring.boot.autoconfigure.properties.TemporalProperties;
import io.temporal.spring.boot.autoconfigure.template.ServiceStubsTemplate;
import io.temporal.spring.boot.autoconfigure.template.TestWorkflowEnvironmentAdapter;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TemporalProperties.class)
@AutoConfigureAfter(
    value = {MetricsScopeAutoConfiguration.class, TestServerAutoConfiguration.class})
@ConditionalOnExpression(
    "${spring.temporal.test-server.enabled:false} || '${spring.temporal.connection.target:}'.length() > 0")
public class ServiceStubsAutoConfiguration {

  ConfigurableListableBeanFactory beanFactory;

  public ServiceStubsAutoConfiguration(ConfigurableListableBeanFactory beanFactory) {
    this.beanFactory = beanFactory;
  }

  @Bean(name = "temporalServiceStubsTemplate")
  public ServiceStubsTemplate serviceStubsTemplate(
      TemporalProperties properties,
      @Qualifier("temporalMetricsScope") @Autowired(required = false) @Nullable Scope metricsScope,
      @Qualifier("temporalTestWorkflowEnvironmentAdapter") @Autowired(required = false) @Nullable
          TestWorkflowEnvironmentAdapter testWorkflowEnvironment,
      @Autowired(required = false) @Nullable
          Map<String, TemporalOptionsCustomizer<WorkflowServiceStubsOptions.Builder>>
              workflowServiceStubsCustomizerMap) {
    List<TemporalOptionsCustomizer<Builder>> workflowServiceStubsCustomizer =
        AutoConfigurationUtils.chooseTemporalCustomizerBeans(
            beanFactory, workflowServiceStubsCustomizerMap, Builder.class, properties);
    return new ServiceStubsTemplate(
        properties.getConnection(),
        metricsScope,
        testWorkflowEnvironment,
        workflowServiceStubsCustomizer);
  }

  @Bean(name = "temporalWorkflowServiceStubs")
  public WorkflowServiceStubs workflowServiceStubsTemplate(
      @Qualifier("temporalServiceStubsTemplate") ServiceStubsTemplate serviceStubsTemplate) {
    return serviceStubsTemplate.getWorkflowServiceStubs();
  }
}
