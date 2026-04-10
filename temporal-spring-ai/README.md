# temporal-spring-ai

Integrates [Spring AI](https://docs.spring.io/spring-ai/reference/) with [Temporal](https://temporal.io/) workflows, making AI model calls, tool execution, vector store operations, embeddings, and MCP tool calls durable Temporal primitives.

## Compatibility

| Dependency | Minimum Version |
|---|---|
| Java | 17 |
| Spring Boot | 3.x |
| Spring AI | 1.1.0 |
| Temporal Java SDK | 1.33.0 |

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
    this.chatClient = TemporalChatClient.builder(chatModel)
            .defaultSystem("You are a helpful assistant.")
            .defaultTools(myActivityStub)
            .build();
}

@Override
public String run(String goal) {
    return chatClient.prompt().user(goal).call().content();
}
```

## Optional Integrations

These are auto-configured when their dependencies are on the classpath:

| Feature | Dependency | What it registers |
|---|---|---|
| Vector Store | `spring-ai-rag` | `VectorStoreActivity` |
| Embeddings | `spring-ai-rag` | `EmbeddingModelActivity` |
| MCP | `spring-ai-mcp` | `McpClientActivity` |
