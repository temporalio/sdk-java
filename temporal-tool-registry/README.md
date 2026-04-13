# temporal-tool-registry

LLM tool-calling primitives for Temporal activities — define tools once, use with
Anthropic or OpenAI.

## Before you start

A Temporal Activity is a function that Temporal monitors and retries automatically on failure. Temporal streams progress between retries via heartbeats — that's the mechanism `AgenticSession` uses to resume a crashed LLM conversation mid-turn.

`ToolRegistry.runToolLoop` works standalone in any function — no Temporal server needed. Add `AgenticSession` only when you need crash-safe resume inside a Temporal activity.

`AgenticSession` requires a running Temporal worker — it reads and writes heartbeat state from the active activity context. Use `ToolRegistry.runToolLoop` standalone for scripts, one-off jobs, or any code that runs outside a Temporal worker.

New to Temporal? → https://docs.temporal.io/develop

## Install

Add to your `build.gradle`:

```groovy
dependencies {
    // Replace VERSION with the latest release from https://search.maven.org
    implementation 'io.temporal:temporal-tool-registry:VERSION'
    // Add only the LLM SDK(s) you use:
    implementation 'com.anthropic:anthropic-java:VERSION'  // Anthropic
    implementation 'com.openai:openai-java:VERSION'        // OpenAI
}
```

## Quickstart

Tool definitions use [JSON Schema](https://json-schema.org/understanding-json-schema/) for `inputSchema`. The quickstart uses a single string field; for richer schemas refer to the JSON Schema docs.

```java
import io.temporal.toolregistry.*;

@ActivityMethod
public List<String> analyze(String prompt) throws Exception {
    List<String> issues = new ArrayList<>();
    ToolRegistry registry = new ToolRegistry();
    registry.register(
        ToolDefinition.builder()
            .name("flag_issue")
            .description("Flag a problem found in the analysis")
            .inputSchema(Map.of(
                "type", "object",
                "properties", Map.of("description", Map.of("type", "string")),
                "required", List.of("description")))
            .build(),
        (Map<String, Object> input) -> {
            issues.add((String) input.get("description"));
            return "recorded"; // this string is sent back to the LLM as the tool result
        });

    AnthropicConfig cfg = AnthropicConfig.builder()
        .apiKey(System.getenv("ANTHROPIC_API_KEY"))
        .build();
    Provider provider = new AnthropicProvider(cfg, registry,
        "You are a code reviewer. Call flag_issue for each problem you find.");

    ToolRegistry.runToolLoop(provider, registry, "" /* system prompt: "" defers to provider default */, prompt);
    return issues;
}
```

### Selecting a model

The default model is `"claude-sonnet-4-6"` (Anthropic) or `"gpt-4o"` (OpenAI). Override with the `model()` builder method:

```java
AnthropicConfig cfg = AnthropicConfig.builder()
    .apiKey(System.getenv("ANTHROPIC_API_KEY"))
    .model("claude-3-5-sonnet-20241022")
    .build();
```

Model IDs are defined by the provider — see Anthropic or OpenAI docs for current names.

### OpenAI

```java
OpenAIConfig cfg = OpenAIConfig.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .build();
Provider provider = new OpenAIProvider(cfg, registry, "your system prompt");
ToolRegistry.runToolLoop(provider, registry, "" /* system prompt: "" defers to provider default */, prompt);
```

## Crash-safe agentic sessions

For multi-turn LLM conversations that must survive activity retries, use
`AgenticSession.runWithSession`. It saves conversation history via
`Activity.getExecutionContext().heartbeat()` on every turn and restores it on retry.

```java
@ActivityMethod
public List<Object> longAnalysis(String prompt) throws Exception {
    List<Object> issues = new ArrayList<>();

    AgenticSession.runWithSession(session -> {
        ToolRegistry registry = new ToolRegistry();
        registry.register(
            ToolDefinition.builder().name("flag").description("...").inputSchema(Map.of("type", "object")).build(),
            input -> { session.addIssue(input); return "ok"; /* sent back to LLM */ });

        AnthropicConfig cfg = AnthropicConfig.builder()
            .apiKey(System.getenv("ANTHROPIC_API_KEY")).build();
        Provider provider = new AnthropicProvider(cfg, registry, "your system prompt");

        session.runToolLoop(provider, registry, "your system prompt", prompt);
        issues.addAll(session.getIssues()); // capture after loop completes
    });

    return issues;
}
```

## Testing without an API key

```java
import io.temporal.toolregistry.testing.*;

@Test
public void testAnalyze() throws Exception {
    ToolRegistry registry = new ToolRegistry();
    registry.register(
        ToolDefinition.builder().name("flag").description("d")
            .inputSchema(Map.of("type", "object")).build(),
        input -> "ok");

    MockProvider provider = new MockProvider(
        MockResponse.toolCall("flag", Map.of("description", "stale API")),
        MockResponse.done("analysis complete"));

    List<Map<String, Object>> msgs =
        ToolRegistry.runToolLoop(provider, registry, "sys", "analyze");
    assertTrue(msgs.size() > 2);
}
```

## Integration testing with real providers

To run the integration tests against live Anthropic and OpenAI APIs:

```bash
RUN_INTEGRATION_TESTS=1 \
  ANTHROPIC_API_KEY=sk-ant-... \
  OPENAI_API_KEY=sk-proj-... \
  ./gradlew test --tests "*.ToolRegistryTest.testIntegration*"
```

Tests skip automatically when `RUN_INTEGRATION_TESTS` is unset. Real API calls
incur billing — expect a few cents per full test run.

## Storing application results

`session.getIssues()` accumulates application-level
results during the tool loop. Elements are serialized to JSON inside each heartbeat
checkpoint — they must be plain maps/dicts with JSON-serializable values. A non-serializable
value raises a non-retryable `ApplicationError` at heartbeat time rather than silently
losing data on the next retry.

### Storing typed results

Convert your domain type to a plain dict at the tool-call site and back after the session:

```java
record Issue(String type, String file) {}

// Inside tool handler:
session.addIssue(Map.of("type", "smell", "file", "Foo.java"));

// After session (using Jackson for convenient mapping):
// requires jackson-databind in your build.gradle:
// implementation 'com.fasterxml.jackson.core:jackson-databind:VERSION'
ObjectMapper mapper = new ObjectMapper();
List<Issue> issues = session.getIssues().stream()
    .map(m -> mapper.convertValue(m, Issue.class))
    .toList();
```

## Per-turn LLM timeout

Individual LLM calls inside the tool loop are unbounded by default. A hung HTTP
connection holds the activity open until Temporal's `ScheduleToCloseTimeout`
fires — potentially many minutes. Set a per-turn timeout on the provider client:

```java
AnthropicConfig cfg = AnthropicConfig.builder()
    .apiKey(System.getenv("ANTHROPIC_API_KEY"))
    .timeout(Duration.ofSeconds(30))
    .build();
Provider provider = new AnthropicProvider(cfg, registry, "your system prompt");
// provider now enforces 30s per turn
```

Recommended timeouts:

| Model type | Recommended |
|---|---|
| Standard (Claude 3.x, GPT-4o) | 30 s |
| Reasoning (o1, o3, extended thinking) | 300 s |

## MCP integration

`ToolRegistry.fromMcpTools` converts a list of `McpTool` descriptors into a populated
registry. Handlers default to no-ops that return an empty string; override them with
`register` after construction.

```java
// mcpTools is List<McpTool> — populate from your MCP client.
ToolRegistry registry = ToolRegistry.fromMcpTools(mcpTools);

// Override specific handlers before running the loop.
registry.register(
    ToolDefinition.builder().name("read_file") /* ... */ .build(),
    input -> readFile((String) input.get("path")));
```

`McpTool` mirrors the MCP protocol's `Tool` object: `name`, `description`, and
`inputSchema` (a `Map<String, Object>` containing a JSON Schema object).
