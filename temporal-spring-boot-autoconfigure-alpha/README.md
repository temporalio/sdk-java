# Temporal Spring Boot

The following Readme assumes that you use Spring Boot yml configuration files (`application.yml`).
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

[Generate PKCS8 or PKCS12 files](https://github.com/temporalio/samples-server/blob/main/tls/client-only/mac/end-entity.sh).
Add the following to your `application.yml` file:

```yml
spring.temporal:
  connection:
    mtls:
      key-file: /path/to/key.key
      cert-chain-file: /path/to/cert.pem # If you use PKCS12 (.pkcs12, .pfx or .p12), you don't need to set it because certificates chain is bundled into the key file
      # key-password: <password_for_the_key>
      # insecure-trust-manager: true # or add ca.pem to java default truststore
```

Alternatively with PKCS8 you can pass the content of the key and certificates chain as strings, which allows to pass them from the environment variable for example:

```yml
spring.temporal:
  connection:
    mtls:
      key: <raw content of the key PEM file>
      cert-chain: <raw content of the cert chain PEM file>
      # key-password: <password_for_the_key>
      # insecure-trust-manager: true # or add ca.pem to java default truststore
```

## Data Converter

Define a bean of type `io.temporal.common.converter.DataConverter` in Spring context to be used as a custom data converter.
If Spring context has several beans of the `DataConverter` type, the context will fail to start. You need to name one of them `mainDataConverter` to resolve ambiguity.

# Workers configuration

There are two ways of configuring workers. Auto-discovery and an explicit configuration.

## Explicit configuration

Follow the pattern to explicitly configure the workers:

```yml
spring.temporal:
  workers:
    - task-queue: your-task-queue-name
      # name: your-worker-name # unique name of the Worker. If not specified, Task Queue is used as a Worker name.
      workflow-classes:
        - your.package.YouWorkflowImpl
      activity-beans:
        - activity-bean-name1
  # start-workers: false # disable auto-start of WorkersFactory and Workers if you want to make any custom changes before the start
```

## Auto-discovery

Allows to skip specifying Workflow classes and Activity beans explicitly in the config
by referencing Worker Task Queue names or Worker Names on Workflow and Activity implementations.
Auto-discovery is applied after and on top of an explicit configuration.

Add the following to your `application.yml` to auto-discover workflows and activities from your classpath.

```yml
spring.temporal:
  workers-auto-discovery:
    packages:
      - your.package # enumerate all the packages that contain your workflow and activity implementations
  # start-workers: false # disable starting WorkersFactory if you want to make any custom changes before the start
```

What is auto-discovered:
- Workflows implementation classes annotated with `io.temporal.spring.boot.WorkflowImpl`
- Activity beans present Spring context whose implementations are annotated with `io.temporal.spring.boot.ActivityImpl`
- Workers if a Task Queue is referenced by the annotations but not explicitly configured. Default configuration will be used.

Auto-discovered workflow implementation classes and activity beans will be registered with the configured workers if not already registered.

### Referencing worker names vs task queues

Application that incorporates
[Task Queue based Versioning strategy](https://community.temporal.io/t/workflow-versioning-strategies/6911)
may choose to use explicit Worker names to reference because it adds a level of indirection.
This way Task Queue name is specified only once in the config and can be easily changed when needed,
while all the auto-discovery annotations reference the Worker by its static name.

An application whose lifecycle doesn't involve changing task queue names may prefer to reference
Task Queue names directly for simplicity.

Note: Worker whose name is not explicitly specified is named after it's Task Queue.

## Customization of `*Options`

To provide freedom in customization of `*Options` instances that are created by this module,
beans that implement `io.temporal.spring.boot.TemporalOptionsCustomizer<OptionsBuilderType>`
interface may be added to the Spring context.

Where `OptionsType` may be one of:
- `WorkflowServiceStubsOptions.Builder`
- `WorkflowClientOption.Builder`
- `WorkerFactoryOptions.Builder`
- `WorkerOptions.Builder`
- `TestEnvironmentOptions.Builder`

`io.temporal.spring.boot.WorkerOptionsCustomizer` may be used instead of `TemporalOptionsCustomizer<WorkerOptions.Builder>`
if `WorkerOptions` needs to be modified differently depending on the Task Queue or Worker name.

# Integrations

## Metrics

You can set up built-in Spring Boot Metrics using Spring Boot Actuator 
following [one of the manuals](https://tanzu.vmware.com/developer/guides/spring-prometheus/). 
This module will pick up the `MeterRegistry` bean configured this way and use to report Temporal Metrics.

Alternatively, you can define a custom `io.micrometer.core.instrument.MeterRegistry` bean in the application context.

## Tracing

You can set up Spring Cloud Sleuth with OpenTelemetry export 
following [one of the manuals](https://betterprogramming.pub/distributed-tracing-with-opentelemetry-spring-cloud-sleuth-kafka-and-jaeger-939e35f45821).
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
  @Autowired ConfigurableApplicationContext applicationContext;
  @Autowired TestWorkflowEnvironment testWorkflowEnvironment;
  @Autowired WorkflowClient workflowClient;

  @BeforeEach
  void setUp() {
    applicationContext.start();
  }

  @Test
  @Timeout(value = 10)
  public void test() {
    # ...
  }

  @ComponentScan # to discover activity beans annotated with @Component
  # @EnableAutoConfiguration # can be used to load only AutoConfigurations if usage of @ComponentScan is not desired 
  public static class Configuration {}
}
```


