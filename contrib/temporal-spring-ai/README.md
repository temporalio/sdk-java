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

## Activity options and retry behavior

`ActivityChatModel.forDefault()` / `forModel(name)` build the chat activity stub with sensible defaults: a 2-minute start-to-close timeout, 3 attempts, and `org.springframework.ai.retry.NonTransientAiException` + `java.lang.IllegalArgumentException` marked non-retryable so a bad API key or invalid prompt fails fast instead of churning through retries.

When you need finer control — a specific task queue, heartbeats, priority, or a custom `RetryOptions` — pass an `ActivityOptions` directly:

```java
ActivityChatModel chatModel = ActivityChatModel.forDefault(
        ActivityOptions.newBuilder(ActivityChatModel.defaultActivityOptions())
                .setTaskQueue("chat-heavy")
                .build());
```

For configuration-driven per-model overrides, declare a `ChatModelActivityOptions` bean and auto-configuration wires the map into the plugin. A key equal to `ChatModelTypes.DEFAULT_MODEL_NAME` (the literal `"default"`) acts as a global catch-all: any chat model that lacks a bean-name-specific entry — including models contributed by third-party starters that your application did not declare directly — picks up that entry.

```java
@Bean
ChatModelActivityOptions chatModelActivityOptions() {
    ActivityOptions fiveMinute = ActivityOptions.newBuilder(ActivityChatModel.defaultActivityOptions())
            .setStartToCloseTimeout(Duration.ofMinutes(5))
            .build();
    return new ChatModelActivityOptions(Map.of(
            ChatModelTypes.DEFAULT_MODEL_NAME, fiveMinute,                             // global baseline
            "claude", ActivityOptions.newBuilder(fiveMinute).setTaskQueue("claude-heavy").build())); // override
}
```

Keys that neither match a registered ChatModel bean name nor equal `"default"` cause plugin construction to fail, so a typo surfaces at startup.

`ActivityMcpClient.create()` / `create(ActivityOptions)` work the same way with a 30-second default timeout.

The Temporal UI labels chat and MCP rows with a short Summary (`chat: <model>`, `mcp: <client>.<tool>`). `ActivityChatModel` and `ActivityMcpClient` are constructed only via these factories — there is no public constructor, so users can't accidentally end up in a code path that skips UI labels. Prompt text is deliberately not included in chat summaries to avoid leaking user input (which may contain PII, credentials, or other sensitive data) into workflow history and server logs.

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

## Migrating from plain Spring AI

The plugin is designed so that bringing an existing Spring AI service onto Temporal is a localized change. Outside Temporal, you probably have something like:

```java
@Service
class AssistantService {
    private final ChatClient chatClient;

    AssistantService(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem("You are a helpful assistant.")
                .defaultTools(new WeatherTools(), new MyTools())
                .build();
    }

    String respond(String goal) {
        return chatClient.prompt().user(goal).call().content();
    }
}
```

Inside a Temporal Workflow it becomes:

```java
@WorkflowInterface
interface AssistantWorkflow { @WorkflowMethod String respond(String goal); }

class AssistantWorkflowImpl implements AssistantWorkflow {
    private final ChatClient chatClient;

    @WorkflowInit
    AssistantWorkflowImpl(String goal) {
        WeatherActivity weather = Workflow.newActivityStub(WeatherActivity.class, opts);
        this.chatClient = TemporalChatClient.builder(ActivityChatModel.forDefault())
                .defaultSystem("You are a helpful assistant.")
                .defaultTools(weather, new MyTools())
                .build();
    }

    @Override
    public String respond(String goal) {
        return chatClient.prompt().user(goal).call().content();
    }
}
```

Three substitutions:

| Outside Temporal | Inside a Temporal workflow |
|---|---|
| `ChatModel chatModel` (injected) | `ActivityChatModel.forDefault()` |
| `ChatClient.builder(chatModel)` | `TemporalChatClient.builder(activityChatModel)` |
| `new WeatherTools()` for a plain POJO tool | `Workflow.newActivityStub(WeatherActivity.class, ...)` for a durable tool |

Plain `@Tool` POJOs, `@SideEffectTool`-annotated classes, and Nexus service stubs all work the same way — see **Tool Types** above.

## Media in messages

If you attach media (images, audio, etc.) to a `UserMessage` or an `AssistantMessage`, prefer passing it by URI rather than raw bytes:

```java
// Good — only the URL crosses the activity boundary.
Media image = new Media(MimeTypeUtils.IMAGE_PNG, URI.create("https://cdn.example.com/pic.png"));

// Works, but size-limited — see below.
Media image = new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(bytes));
```

Raw `byte[]` media gets serialized into every chat activity's input *and* result payload, which end up inside Temporal workflow history events. Server-side history events have a fixed 2 MiB size limit; to leave headroom for messages, tool definitions, and options, the plugin enforces a **1 MiB default cap** on inline media bytes and fails fast with an `IllegalArgumentException` pointing you at the URI alternative.

Override the cap by setting the system property `io.temporal.springai.maxMediaBytes` before your worker starts (pass a positive integer; `0` disables the check). For anything larger than a small thumbnail, the URI route is the right answer — have an activity write the bytes to blob storage, then pass only the URL into the conversation.

## Known limitations

- **Streaming (`chatClient.stream(...)`)** — not currently supported. Use `.call()` instead.
- **`defaultToolContext(Map<String, Object>)`** — not supported; tool context holds mutable state that can't safely cross the activity boundary. Pass required context as activity parameters or workflow state.
- **Child workflow stubs as tools** — not supported. Wrap a plain `@Tool` method that starts the child workflow via `Workflow.newChildWorkflowStub(...)` and call through to it yourself.
- **Media `byte[]` size** — inline bytes are capped at 1 MiB per payload (see "Media in messages" above). Prefer URI-based media.
- **Provider-specific `ChatOptions` via `ChatClient.defaultOptions(...)`** — works as long as your `ChatOptions` subclass overrides `copy()` to return its own type (every real provider class does this). A subclass inheriting the default `copy()` loses its identity before the plugin sees it — same behavior as outside Temporal.

## Observability

`TemporalChatClient.builder(chatModel, observationRegistry, customConvention)` accepts a Micrometer `ObservationRegistry` for Spring AI-side chat client metrics. Temporal-side metrics (activity durations, retries) are emitted by the SDK's `MetricsScope` — see the [Temporal Java SDK observability docs](https://docs.temporal.io/develop/java/observability) for how to wire an OpenTelemetry or Prometheus exporter onto your workers. The two layers compose: Spring AI observations cover what the caller does; Temporal metrics cover what the scheduled activity does.

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
