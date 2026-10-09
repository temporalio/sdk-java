# Temporal Spring Boot

For documentation on the Temporal Spring Boot Integration, please visit [https://docs.temporal.io/develop/java/spring-boot-integration](https://docs.temporal.io/develop/java/spring-boot-integration)

## Worker shutdown

Closing the Spring application context initiates graceful shutdown for workers in the root and
non-root namespaces and waits for in-flight tasks before destroying their dependencies. With
Spring Boot's JVM shutdown hook enabled, both SIGINT and SIGTERM follow this path on POSIX systems.
Worker startup still follows `spring.temporal.start-workers` and its per-namespace overrides.

The wait is bounded by `spring.lifecycle.timeout-per-shutdown-phase` (30 seconds by default).
For example, set it to `60s` to allow more time for activities to finish. When the timeout expires,
Spring continues closing the context; the integration does not interrupt activities with
`shutdownNow()`. Allow enough process termination time for the configured grace period.

Applications that disable Spring Boot's JVM shutdown hook must close the application context
themselves. Standalone `WorkerFactory` applications manage their own shutdown; a JVM shutdown hook
should call `shutdown()` followed by `awaitTermination(timeout, unit)` before closing service stubs.

# Running Multiple Name Space (experimental)

Along with the root namespace, you can configure multiple non-root namespaces in the application.yml file. Different namespaces can have different configurations including but not limited to different connection options, registered workflows/activities, data converters etc.

```yml
spring.temporal:
    namespaces:
      - namespace: assign
        alias: assign
        workers-auto-discovery:
          packages: com.component.temporal.assign
        workers:
          - task-queue: global
      - namespace: unassign
        alias: unassign
        workers-auto-discovery:
          packages: com.component.temporal.unassign
        workers:
          - task-queue: global
```

## Customization

All customization points for the root namespace also exist for the non-root namespaces. To specify for a particular 
namespace users just need to append the alias/namespace to the bean. Currently, auto registered interceptors are not 
supported, but `WorkerFactoryOptions` can always be used to customize it per namespace.

```java
    // TemporalOptionsCustomizer type beans must start with the namespace/alias you defined and end with function class 
    // name you want to customizer and concat Customizer as the bean name.
    @Bean
    TemporalOptionsCustomizer<WorkflowServiceStubsOptions.Builder> assignWorkflowServiceStubsCustomizer() {
        return builder -> builder.setKeepAliveTime(Duration.ofHours(1));
    }

    // Data converter is also supported
    @Bean
    DataConverter assignDataConverter() {
        return DataConverter.getDefaultInstance();
    }
```

## Injecting

If you want to autowire different `WorkflowClient` instances from different namespaces, you can use the `@Resource` 
annotation with the bean name corresponding to the namespace alias + `WorkflowClient`:

```java
    // temporalWorkflowClient is the primary and rootNamespace bean. 
    @Resource
    WorkflowClient workflowClient;

    // Bean name here corresponds to the namespace/alias + Simple Class Name
    @Resource(name = "assignWorkflowClient")
    private WorkflowClient assignWorkflowClient;

    @Resource(name = "unassignWorkflowClient")
    private WorkflowClient unassignWorkflowClient;
```
