package io.temporal.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import io.opentracing.Tracer;
import io.temporal.client.ActivityClient;
import io.temporal.client.ActivityClientOptions;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.interceptors.ActivityClientInterceptor;
import io.temporal.common.interceptors.ActivityClientInterceptorBase;
import io.temporal.opentracing.OpenTracingActivityClientInterceptor;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.spring.boot.autoconfigure.template.ClientTemplate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;

class ActivityClientAutoConfigurationTest {
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  TestServerAutoConfiguration.class,
                  ServiceStubsAutoConfiguration.class,
                  RootNamespaceAutoConfiguration.class,
                  NonRootNamespaceAutoConfiguration.class))
          .withPropertyValues(
              "spring.temporal.connection.target=local",
              "spring.temporal.namespace=root",
              "spring.temporal.start-workers=false");

  @Test
  void createsClientWithoutOptionalConfiguration() {
    contextRunner.run(
        context -> {
          assertThat(context).hasNotFailed().hasSingleBean(ActivityClient.class);
          ActivityClient client = context.getBean(ActivityClient.class);
          assertThat(client).isSameAs(context.getBean("temporalActivityClient"));
          assertThat(client).isSameAs(context.getBean(ClientTemplate.class).getActivityClient());
          assertThat(client.getHandle("test", null)).isNotNull();
        });
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void appliesRootOptionsAndOrderedCustomizers(boolean testServer) {
    contextRunner
        .withUserConfiguration(ClientConfiguration.class)
        .withPropertyValues("spring.temporal.test-server.enabled=" + testServer)
        .run(
            context -> {
              assertThat(context).hasNotFailed().hasSingleBean(ActivityClient.class);
              ActivityClientOptions options = context.getBean(CapturedOptions.class).get("root");
              assertThat(options.getNamespace()).isEqualTo("root");
              assertThat(options.getIdentity()).isEqualTo("last");
              assertThat(options.getDataConverter()).isSameAs(context.getBean(DataConverter.class));
              ActivityClientInterceptor interceptor =
                  context.getBean(ActivityClientInterceptor.class);
              assertThat(options.getInterceptors()).containsExactly(interceptor);
              verify(interceptor).activityClientCallsInterceptor(any());
            });
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void registersTracingBeforeExplicitInterceptors(boolean testServer) {
    contextRunner
        .withUserConfiguration(ClientConfiguration.class)
        .withBean(Tracer.class, () -> mock(Tracer.class))
        .withPropertyValues("spring.temporal.test-server.enabled=" + testServer)
        .run(
            context -> {
              assertThat(context).hasNotFailed().hasSingleBean(ActivityClient.class);
              ActivityClientOptions options = context.getBean(CapturedOptions.class).get("root");
              ActivityClientInterceptor interceptor =
                  context.getBean(ActivityClientInterceptor.class);
              assertThat(options.getInterceptors()).hasSize(2);
              assertThat(options.getInterceptors().get(0))
                  .isInstanceOf(OpenTracingActivityClientInterceptor.class);
              assertThat(options.getInterceptors().get(1)).isSameAs(interceptor);
              verify(interceptor).activityClientCallsInterceptor(any());
            });
  }

  @Test
  void appliesNonRootOptionsByAliasOrNamespace() {
    contextRunner
        .withUserConfiguration(ClientConfiguration.class, NonRootConfiguration.class)
        .withPropertyValues(
            "spring.temporal.namespaces[0].namespace=namespace1",
            "spring.temporal.namespaces[0].alias=ns1",
            "spring.temporal.namespaces[1].namespace=namespace2")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBeansOfType(ActivityClient.class))
                  .containsOnlyKeys(
                      "temporalActivityClient", "ns1ActivityClient", "namespace2ActivityClient");
              assertThat(context.getBean(ActivityClient.class))
                  .isSameAs(context.getBean("temporalActivityClient"));
              CapturedOptions captured = context.getBean(CapturedOptions.class);
              assertThat(captured.get("root").getNamespace()).isEqualTo("root");
              assertThat(captured.get("root").getIdentity()).isEqualTo("last");
              ActivityClientOptions ns1Options = captured.get("ns1");
              assertThat(ns1Options.getNamespace()).isEqualTo("namespace1");
              assertThat(ns1Options.getIdentity()).isEqualTo("ns1");
              assertThat(ns1Options.getDataConverter())
                  .isSameAs(context.getBean("ns1DataConverter"));
              assertThat(ns1Options.getInterceptors()).isEmpty();
              ActivityClientOptions ns2Options = captured.get("namespace2");
              assertThat(ns2Options.getNamespace()).isEqualTo("namespace2");
              assertThat(ns2Options.getIdentity()).isEqualTo("namespace2");
              assertThat(ns2Options.getDataConverter())
                  .isSameAs(context.getBean("namespace2DataConverter"));
              assertThat(ns2Options.getInterceptors()).isEmpty();
              verify(context.getBean(ActivityClientInterceptor.class))
                  .activityClientCallsInterceptor(any());
            });
  }

  static class CapturedOptions {
    private final Map<String, ActivityClientOptions> options = new HashMap<>();

    ActivityClientOptions get(String prefix) {
      return options.get(prefix);
    }

    ActivityClientOptions.Builder capture(String prefix, ActivityClientOptions.Builder builder) {
      options.put(prefix, builder.build());
      return builder;
    }
  }

  static class ClientConfiguration {
    @Bean
    CapturedOptions capturedOptions() {
      return new CapturedOptions();
    }

    @Bean
    DataConverter rootDataConverter() {
      return DefaultDataConverter.newDefaultInstance();
    }

    @Bean
    ActivityClientInterceptor activityClientInterceptor() {
      return spy(new ActivityClientInterceptorBase());
    }

    @Bean
    @Order(1)
    TemporalOptionsCustomizer<ActivityClientOptions.Builder> firstActivityClientCustomizer() {
      return builder -> builder.setIdentity("first");
    }

    @Bean
    @Order(2)
    TemporalOptionsCustomizer<ActivityClientOptions.Builder> lastActivityClientCustomizer(
        CapturedOptions captured) {
      return builder -> {
        assertThat(builder.build().getIdentity()).isEqualTo("first");
        return captured.capture(
            "root", ActivityClientOptions.newBuilder(builder.build()).setIdentity("last"));
      };
    }
  }

  static class NonRootConfiguration {
    @Bean
    DataConverter ns1DataConverter() {
      return DefaultDataConverter.newDefaultInstance();
    }

    @Bean
    DataConverter namespace2DataConverter() {
      return DefaultDataConverter.newDefaultInstance();
    }

    @Bean
    TemporalOptionsCustomizer<ActivityClientOptions.Builder> ns1ActivityClientCustomizer(
        CapturedOptions captured) {
      return builder -> captured.capture("ns1", builder.setIdentity("ns1"));
    }

    @Bean
    TemporalOptionsCustomizer<ActivityClientOptions.Builder> namespace2ActivityClientCustomizer(
        CapturedOptions captured) {
      return builder -> captured.capture("namespace2", builder.setIdentity("namespace2"));
    }
  }
}
