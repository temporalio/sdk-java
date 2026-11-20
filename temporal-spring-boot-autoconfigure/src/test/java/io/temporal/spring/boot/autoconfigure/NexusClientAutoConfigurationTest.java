package io.temporal.spring.boot.autoconfigure;

import static org.junit.jupiter.api.Assertions.*;

import io.temporal.client.NexusClient;
import io.temporal.client.NexusClientOptions;
import io.temporal.client.NexusOperationExecutionCount;
import io.temporal.client.WorkflowClient;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.interceptors.NexusClientCallsInterceptor;
import io.temporal.common.interceptors.NexusClientCallsInterceptorBase;
import io.temporal.common.interceptors.NexusClientInterceptor;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.spring.boot.autoconfigure.template.ClientTemplate;
import io.temporal.testing.TestWorkflowEnvironment;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;

public class NexusClientAutoConfigurationTest {
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  TestServerAutoConfiguration.class,
                  ServiceStubsAutoConfiguration.class,
                  RootNamespaceAutoConfiguration.class,
                  NonRootNamespaceAutoConfiguration.class))
          .withPropertyValues(
              "spring.temporal.connection.target=127.0.0.1:7233",
              "spring.temporal.start-workers=false");

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  public void clientIsAvailableWithoutWorkersOrCustomizers(boolean testServer) {
    contextRunner
        .withPropertyValues("spring.temporal.test-server.enabled=" + testServer)
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              NexusClient client = context.getBean(NexusClient.class);
              assertSame(client, context.getBean("temporalNexusClient"));
              assertSame(client, context.getBean(ClientTemplate.class).getNexusClient());
              assertSame(
                  context.getBean(WorkflowClient.class).getWorkflowServiceStubs().getRawChannel(),
                  client.getWorkflowServiceStubs().getRawChannel());
              assertNotNull(client.getHandle("operation-id", null, String.class));
              assertNotNull(client.newUntypedNexusServiceClient("endpoint", "service"));
            });
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  public void clientsUseNamespaceSpecificOptionsAndConnections(boolean testServer) {
    contextRunner
        .withUserConfiguration(CustomizationConfiguration.class)
        .withPropertyValues(
            "spring.temporal.test-server.enabled=" + testServer,
            "spring.temporal.namespace=root",
            "spring.temporal.namespaces[0].namespace=applications",
            "spring.temporal.namespaces[0].alias=apps",
            "spring.temporal.namespaces[1].namespace=support",
            "spring.temporal.namespaces[1].connection.target=127.0.0.1:7234")
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertEquals(3, context.getBeansOfType(NexusClient.class).size());
              NexusClient root = context.getBean(NexusClient.class);
              NexusClient apps = context.getBean("appsNexusClient", NexusClient.class);
              NexusClient support = context.getBean("supportNexusClient", NexusClient.class);
              assertSame(root, context.getBean("temporalNexusClient"));
              NexusClients injected = context.getBean(NexusClients.class);
              assertSame(root, injected.root);
              assertSame(apps, injected.apps);
              assertSame(support, injected.support);
              assertNotSame(root, apps);
              assertNotSame(apps, support);
              assertSame(
                  apps,
                  context.getBean("appsClientTemplate", ClientTemplate.class).getNexusClient());
              assertSame(
                  support,
                  context.getBean("supportClientTemplate", ClientTemplate.class).getNexusClient());
              assertSame(
                  context
                      .getBean("appsWorkflowServiceStubs", WorkflowServiceStubs.class)
                      .getRawChannel(),
                  apps.getWorkflowServiceStubs().getRawChannel());
              assertSame(
                  context
                      .getBean("supportWorkflowServiceStubs", WorkflowServiceStubs.class)
                      .getRawChannel(),
                  support.getWorkflowServiceStubs().getRawChannel());
              if (testServer) {
                WorkflowServiceStubs testStubs =
                    context
                        .getBean(TestWorkflowEnvironment.class)
                        .getWorkflowClient()
                        .getWorkflowServiceStubs();
                assertSame(
                    testStubs.getRawChannel(), root.getWorkflowServiceStubs().getRawChannel());
                assertSame(
                    testStubs.getRawChannel(), apps.getWorkflowServiceStubs().getRawChannel());
                assertSame(
                    testStubs.getRawChannel(), support.getWorkflowServiceStubs().getRawChannel());
              } else {
                assertEquals(
                    "127.0.0.1:7233", apps.getWorkflowServiceStubs().getOptions().getTarget());
                assertEquals(
                    "127.0.0.1:7234", support.getWorkflowServiceStubs().getOptions().getTarget());
              }

              OptionsRecorder recorder = context.getBean(OptionsRecorder.class);
              assertEquals(3, recorder.options.size());
              assertEquals(4, recorder.customizerCalls);
              NexusClientOptions rootOptions = recorder.options.get("root");
              assertEquals("root", rootOptions.getNamespace());
              assertEquals("root-identity", rootOptions.getIdentity());
              assertSame(context.getBean("rootDataConverter"), rootOptions.getDataConverter());
              assertEquals(
                  Collections.singletonList(context.getBean(NexusClientInterceptor.class)),
                  rootOptions.getInterceptors());
              NexusClientOptions appsOptions = recorder.options.get("apps");
              assertEquals("applications", appsOptions.getNamespace());
              assertEquals("apps-identity", appsOptions.getIdentity());
              assertSame(context.getBean("appsDataConverter"), appsOptions.getDataConverter());
              assertTrue(appsOptions.getInterceptors().isEmpty());
              NexusClientOptions supportOptions = recorder.options.get("support");
              assertEquals("support", supportOptions.getNamespace());
              assertEquals("support-identity", supportOptions.getIdentity());
              assertSame(
                  context.getBean("supportDataConverter"), supportOptions.getDataConverter());

              assertEquals(42, root.countNexusOperationExecutions(null).getCount());
              assertEquals(1, recorder.interceptorCalls.get());
            });
  }

  static class OptionsRecorder {
    final Map<String, NexusClientOptions> options = new HashMap<>();
    final AtomicInteger interceptorCalls = new AtomicInteger();
    int customizerCalls;

    NexusClientOptions.Builder record(String name, NexusClientOptions.Builder options) {
      customizerCalls++;
      assertNull(this.options.put(name, options.build()));
      return options;
    }
  }

  static class CustomizationConfiguration {
    @Bean
    OptionsRecorder optionsRecorder() {
      return new OptionsRecorder();
    }

    @Bean
    DataConverter rootDataConverter() {
      return DefaultDataConverter.newDefaultInstance();
    }

    @Bean
    DataConverter appsDataConverter() {
      return DefaultDataConverter.newDefaultInstance();
    }

    @Bean
    DataConverter supportDataConverter() {
      return DefaultDataConverter.newDefaultInstance();
    }

    @Bean
    NexusClientInterceptor nexusClientInterceptor(OptionsRecorder recorder) {
      return next ->
          new NexusClientCallsInterceptorBase(next) {
            @Override
            public NexusClientCallsInterceptor.CountNexusOperationExecutionsOutput
                countNexusOperationExecutions(
                    NexusClientCallsInterceptor.CountNexusOperationExecutionsInput input) {
              recorder.interceptorCalls.incrementAndGet();
              return new NexusClientCallsInterceptor.CountNexusOperationExecutionsOutput(
                  new NexusOperationExecutionCount(42, Collections.emptyList()));
            }
          };
    }

    @Bean
    @Order(2)
    TemporalOptionsCustomizer<NexusClientOptions.Builder> lastNexusClientCustomizer(
        OptionsRecorder recorder) {
      return options -> {
        assertEquals("first-identity", options.build().getIdentity());
        return recorder.record("root", options.setIdentity("root-identity"));
      };
    }

    @Bean
    @Order(1)
    TemporalOptionsCustomizer<NexusClientOptions.Builder> firstNexusClientCustomizer(
        OptionsRecorder recorder) {
      return options -> {
        recorder.customizerCalls++;
        return NexusClientOptions.newBuilder(options.build()).setIdentity("first-identity");
      };
    }

    @Bean
    TemporalOptionsCustomizer<NexusClientOptions.Builder> appsNexusClientCustomizer(
        OptionsRecorder recorder) {
      return options -> recorder.record("apps", options.setIdentity("apps-identity"));
    }

    @Bean
    TemporalOptionsCustomizer<NexusClientOptions.Builder> supportNexusClientCustomizer(
        OptionsRecorder recorder) {
      return options -> recorder.record("support", options.setIdentity("support-identity"));
    }

    @Bean
    NexusClients nexusClients(
        NexusClient root,
        @Qualifier("appsNexusClient") NexusClient apps,
        @Qualifier("supportNexusClient") NexusClient support) {
      return new NexusClients(root, apps, support);
    }
  }

  static class NexusClients {
    final NexusClient root;
    final NexusClient apps;
    final NexusClient support;

    NexusClients(NexusClient root, NexusClient apps, NexusClient support) {
      this.root = root;
      this.apps = apps;
      this.support = support;
    }
  }
}
