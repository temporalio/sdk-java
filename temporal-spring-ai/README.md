# `temporal-spring-ai`: Temporal + Spring AI Integration

Integrates [Spring AI](https://docs.spring.io/spring-ai/reference/) with [Temporal](https://temporal.io/) workflows, making AI model calls, tool execution, vector store operations, embeddings, and MCP tool calls durable Temporal primitives.

> [!WARNING]
> `temporal-spring-ai` is currently in Public Preview, and will continue to evolve and improve.
> We would love to hear your feedback - positive or negative - over in the [Community Slack](https://t.mp/slack), in the [#topic-ai channel](https://temporalio.slack.com/archives/C0818FQPYKY)


## Compatibility

| Dependency        | Minimum Version |
|-------------------|-----------------|
| Java              | 17              |
| Spring Boot       | 3.x             |
| Spring AI         | 1.1.0           |
| Temporal Java SDK | 1.35.0          |

## Quick Start

Add the dependency (Maven):

```xml
<dependency>
    <groupId>io.temporal</groupId>
    <artifactId>temporal-spring-ai</artifactId>
    <version>${temporal-sdk.version}</version>
</dependency>
```

You also need `temporal-spring-boot-starter` and a Spring AI model starter (e.g. `spring-ai-starter-model-openai`).

The plugin auto-registers `ChatModelActivity` with all Temporal workers. In your workflow:

```java
@WorkflowInit
public MyWorkflowImpl(String goal) {
    ActivityChatModel chatModel = ActivityChatModel.forDefault();

    WeatherActivity weather = Workflow.newActivityStub(WeatherActivity.class, opts);

    this.chatClient = TemporalChatClient.builder(chatModel)
            .defaultSystem("You are a helpful assistant.")
            .defaultTools(weather, new MyTools())
            .build();
}

@Override
public String run(String goal) {
    return chatClient.prompt().user(goal).call().content();
}
```

## Tool Types

Tools passed to `defaultTools()` are handled based on their type:

### Activity stubs

Interfaces annotated with both `@ActivityInterface` and `@Tool` methods. Auto-detected and executed as durable Temporal activities with retries and timeouts.

```java
@ActivityInterface
public interface WeatherActivity {
    @Tool(description = "Get weather for a city") @ActivityMethod
    String getWeather(String city);
}
```

### `@SideEffectTool`

Classes annotated with `@SideEffectTool`. Each `@Tool` method is wrapped in `Workflow.sideEffect()` — the result is recorded in history on first execution and replayed from history on subsequent replays. Use for cheap non-deterministic operations (timestamps, UUIDs).

```java
@SideEffectTool
public class TimestampTools {
    @Tool(description = "Get current time")
    public String now() { return Instant.now().toString(); }
}
```

### Plain tools

Any class with `@Tool` methods that isn't a stub or `@SideEffectTool`. Executes directly in the workflow thread. The user is responsible for determinism — call activities, `Workflow.sideEffect()`, child workflows, etc. as needed.

```java
public class MyTools {
    @Tool(description = "Process data")
    public String process(String input) {
        SomeActivity act = Workflow.newActivityStub(SomeActivity.class, opts);
        return act.doWork(input);
    }
}
```

### Nexus service stubs

Auto-detected and executed as Nexus operations, similar to activity stubs.

## Optional Integrations

Auto-configured when their dependencies are on the classpath:

| Feature      | Dependency      | What it registers        |
|--------------|-----------------|--------------------------|
| Vector Store | `spring-ai-rag` | `VectorStoreActivity`    |
| Embeddings   | `spring-ai-rag` | `EmbeddingModelActivity` |
| MCP          | `spring-ai-mcp` | `McpClientActivity`      |

These can also be set up programmatically without auto-configuration:

```java
new VectorStorePlugin(vectorStore)
new EmbeddingModelPlugin(embeddingModel)
new McpPlugin()
```
