Temporal Spring Boot

# Getting Started

The following Readme assumes that you use Spring Boot yml configuration files (application.yml).
It should be trivial to adjust if your application uses .properties configuration.
Your application should be a `@SpringBootApplication` 
and have `io.temporal:temporal-spring-boot-starter-alpha:${temporalVersion}` added as a dependency.

# Connection setup

The following configuration connects to a locally started Temporal Server 
(see [Temporal Docker Compose](https://github.com/temporalio/docker-compose) or [Tempotalite](https://github.com/temporalio/temporalite))

```yml
spring.temporal:
  connection:
    target: local # you can specify a host:port here for a remote connection
    # enable-https: true
  # namespace: default # you can specify a custom namespace that you are using
```

This will be enough to be able to autowire a `WorkflowClient` in your SpringBoot app:

```java
@SpringBootApplication
class App {
  @Autowire
  private WorkflowClient workflowClient;
}
```

## mTLS

[Generate PKCS8 or PKCS12 files](https://github.com/temporalio/samples-server/blob/main/tls/client-only/mac/end-entity.sh), 
Add the following to your application.yml file:

```yml
spring.temporal:
  connection:
    mtls:
      key-file: /path/to/key.key
      cert-chain-file: /path/to/cert.pem # If you use PKCS12 (.pkcs12, .pfx or .p12), you don't need to set it because certificates chain is bundled into the key file
      # key-password: <password_for_the_key>
      # insecure-trust-manager: true # or add ca.pem to java default truststore
```

Alternatively with PKCS8 you can use

```yml
spring.temporal:
  connection:
    mtls:
      key: <raw content of the key PEM file>
      cert-chain: <raw content of the cert chain PEM file>
      # key-password: <password_for_the_key>
      # insecure-trust-manager: true # or add ca.pem to java default truststore
```

to set the key and cert chain directly from the environment variables for example.

# Configure workers

## Auto-discovery

Add the following to your `application.yml` to auto-discover the workers from your classpath.

```yml
spring.temporal:
  workers-auto-discovery:
    packages:
      - your.package # enumerate all the packages that contain your workflow and activity implementations
  # start-workers: false # disable starting WorkersFactory if you want to make any custom changes before the start
```

What is auto-discovered:
- Workflows implementation classes annotated with `io.temporal.spring.boot.WorkflowImpl`
- Activity beans registered in Spring context which implementation classes are annotated with `io.temporal.spring.boot.ActivityImpl`

Auto-discovered workflow implementation classes and activity beans will be registered with workers.

## Explicit configuration

Follow the pattern to explicitly configure the workers:

```yml
spring.temporal:
  workers:
    - task-queue: your-task-queue-name
      workflow-classes:
        - your.package.YouWorkflowImpl
      activity-beans:
        - activity-bean-name1
  # start-workers: false # disable starting WorkersFactory if you want to make any custom changes before the start
```

## Note on mixing configuration styles

The behavior when both auto-discovery and explicit configuration is mixed is undefined and to be decided later.

# Integrations

## Metrics

You can set up built-in Spring Boot Metrics using Spring Boot Actuator 
following [one of the manuals](https://tanzu.vmware.com/developer/guides/spring-prometheus/). 
This module will pick up the `MeterRegistry` bean configured this way and use to report Temporal Metrics.

Alternatively, you can define a custom `io.micrometer.core.instrument.MeterRegistry` bean in the application context.

## Tracing

You can set up Spring Cloud Sleuth with OpenTelemetry export 
following [one of the manuals](https://reflectoring.io/spring-boot-tracing/).
This module will pick up the `OpenTelemetry` bean configured by `spring-cloud-sleuth-otel-autoconfigure` and use it for Temporal Traces.

Alternatively, you can define a custom 
- `io.opentelemetry.api.OpenTelemetry` for OpenTelemetry or
- `io.opentracing.Tracer` for Opentracing 
bean in the application context.

# Testing

Add the following to your `application.yml` to reconfigure the assembly to work through 
`io.temporal.testing.TestWorkflowEnvironment` that uses in-memory Java Test Server:

```yml
spring.temporal:
  test-server:
    enabled: true
```

When `spring.temporal.test-server.enabled:true` is added, `spring.temporal.connection` section is ignored.
This allows to wire `TestWorkflowEnvironment` bean in your unit tests:

```yml
@SpringBootTest(classes = Test.Configuration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class Test {
  @Autowired TestWorkflowEnvironment testWorkflowEnvironment;
  @Autowired WorkflowClient workflowClient;

  @Test
  @Timeout(value = 10)
  public void testAutoDiscovery() {
    # ...
  }

  @ComponentScan # to discover activity beans annotated with @Component
  # @EnableAutoConfiguration # can be used to load only AutoConfigurations if usage of @ComponentScan is not desired 
  public static class Configuration {}
}
```


