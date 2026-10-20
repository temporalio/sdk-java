# Temporal Spring Boot

For documentation on the Temporal Spring Boot Integration, please visit [https://docs.temporal.io/develop/java/spring-boot-integration](https://docs.temporal.io/develop/java/spring-boot-integration)

# Standalone Activity Client

Spring Boot auto-configures a primary `ActivityClient` bean named `temporalActivityClient` for the root namespace.
Each non-root namespace has a bean named `<alias-or-namespace>ActivityClient`, using its alias when configured,
otherwise its namespace. Each client uses its namespace's data converter and the same service stubs and connection
as its `ScheduleClient`.

For example, configure a connection and an additional namespace:

```yml
spring.temporal:
    namespace: default
    connection:
      target: localhost:7233
    namespaces:
      - namespace: assign
        alias: assign
```

Inject the root or a named non-root client and execute a standalone activity:

```java
import io.temporal.client.ActivityClient;
import io.temporal.client.StartActivityOptions;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class StandaloneActivities {
    private final ActivityClient rootClient;
    private final ActivityClient assignClient;

    public StandaloneActivities(
            ActivityClient rootClient,
            @Qualifier("assignActivityClient") ActivityClient assignClient) {
        this.rootClient = rootClient;
        this.assignClient = assignClient;
    }

    public String greet(String name) {
        StartActivityOptions options = StartActivityOptions.newBuilder()
                .setId(UUID.randomUUID().toString())
                .setTaskQueue("greetings")
                .setStartToCloseTimeout(Duration.ofSeconds(30))
                .build();
        return rootClient.execute("Greet", String.class, options, name);
    }
}
```

Use `TemporalOptionsCustomizer<ActivityClientOptions.Builder>` beans to customize the root client; multiple
customizers are applied in Spring order (for example, using `@Order`). For a non-root namespace, name its
customizer `<alias-or-namespace>ActivityClientCustomizer`, such as `assignActivityClientCustomizer`.
`ActivityClientInterceptor` beans are automatically registered only on the root client, just like schedule
client interceptors. Set non-root interceptors through that namespace's activity client options customizer.

Standalone activities are not supported by the test server.

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