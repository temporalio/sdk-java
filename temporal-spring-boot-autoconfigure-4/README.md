# Temporal Spring Boot (Spring Boot 4)

> **This module supports Spring Boot 4.x only (requires Java 17+).**
> For Spring Boot 2/3, see [`temporal-spring-boot-autoconfigure`](../temporal-spring-boot-autoconfigure/).

For documentation on the Temporal Spring Boot Integration, please visit [https://docs.temporal.io/develop/java/spring-boot-integration](https://docs.temporal.io/develop/java/spring-boot-integration)

## Architecture

This module shares source code with `temporal-spring-boot-autoconfigure` via `temporal-spring-boot-shared/src/`. Both modules compile the same shared source files against their respective Spring Boot versions.

The only version-specific files are the 7 configuration properties classes in `src/main/java/`, which differ in `@ConstructorBinding` usage (not needed in Spring Boot 4).

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
