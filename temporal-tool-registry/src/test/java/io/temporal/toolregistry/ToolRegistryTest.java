package io.temporal.toolregistry;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Assume;
import org.junit.Test;

/** Unit tests for {@link ToolRegistry} and {@link ToolRegistry#runToolLoop}. */
public class ToolRegistryTest {

  // ── dispatch ──────────────────────────────────────────────────────────────────

  @Test
  public void testDispatch_basicCall() throws Exception {
    ToolRegistry registry = new ToolRegistry();
    registry.register(
        ToolDefinition.builder()
            .name("greet")
            .description("greets a user")
            .inputSchema(Collections.singletonMap("type", "object"))
            .build(),
        input -> "hello " + input.get("name"));

    String result = registry.dispatch("greet", Collections.singletonMap("name", "world"));
    assertEquals("hello world", result);
  }

  @Test
  public void testDispatch_unknownTool() {
    ToolRegistry registry = new ToolRegistry();
    try {
      registry.dispatch("missing", Collections.emptyMap());
      fail("expected exception");
    } catch (Exception e) {
      assertTrue(e.getMessage().contains("missing"));
    }
  }

  @Test
  public void testDispatch_handlerException_propagates() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(
        ToolDefinition.builder()
            .name("boom")
            .description("always fails")
            .inputSchema(Collections.singletonMap("type", "object"))
            .build(),
        input -> {
          throw new RuntimeException("kaboom");
        });

    try {
      registry.dispatch("boom", Collections.emptyMap());
      fail("expected exception");
    } catch (Exception e) {
      assertTrue(e.getMessage().contains("kaboom"));
    }
  }

  // ── definitions ───────────────────────────────────────────────────────────────

  @Test
  public void testDefinitions_returnsCopy() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(
        ToolDefinition.builder()
            .name("a")
            .description("d")
            .inputSchema(Collections.singletonMap("type", "object"))
            .build(),
        input -> "ok");

    List<ToolDefinition> defs = registry.definitions();
    assertEquals(1, defs.size());
  }

  @Test
  public void testDefinitions_multipleTools() {
    ToolRegistry registry = new ToolRegistry();
    for (String name : Arrays.asList("alpha", "beta", "gamma")) {
      registry.register(
          ToolDefinition.builder()
              .name(name)
              .description("d")
              .inputSchema(Collections.singletonMap("type", "object"))
              .build(),
          input -> "ok");
    }
    assertEquals(3, registry.definitions().size());
    assertEquals("alpha", registry.definitions().get(0).getName());
  }

  // ── toAnthropic ───────────────────────────────────────────────────────────────

  @Test
  public void testToAnthropic_structure() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(
        ToolDefinition.builder()
            .name("my_tool")
            .description("does something")
            .inputSchema(Map.of("type", "object"))
            .build(),
        input -> "ok");

    List<Map<String, Object>> result = registry.toAnthropic();
    assertEquals(1, result.size());
    assertEquals("my_tool", result.get(0).get("name"));
    assertEquals("does something", result.get(0).get("description"));
    assertNotNull(result.get(0).get("input_schema"));
  }

  // ── toOpenAI ──────────────────────────────────────────────────────────────────

  @Test
  public void testToOpenAI_structure() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(
        ToolDefinition.builder()
            .name("my_tool")
            .description("does something")
            .inputSchema(
                Map.of("type", "object", "properties", Map.of("x", Map.of("type", "string"))))
            .build(),
        input -> "ok");

    List<Map<String, Object>> result = registry.toOpenAI();
    assertEquals(1, result.size());
    assertEquals("function", result.get(0).get("type"));
    @SuppressWarnings("unchecked")
    Map<String, Object> fn = (Map<String, Object>) result.get(0).get("function");
    assertEquals("my_tool", fn.get("name"));
    assertEquals("does something", fn.get("description"));
    assertNotNull(fn.get("parameters"));
  }

  // ── runToolLoop ───────────────────────────────────────────────────────────────

  @Test
  public void testRunToolLoop_singleDone() throws Exception {
    ToolRegistry registry = new ToolRegistry();
    io.temporal.toolregistry.testing.MockProvider provider =
        new io.temporal.toolregistry.testing.MockProvider(
            io.temporal.toolregistry.testing.MockResponse.done("finished"));

    List<Map<String, Object>> msgs = ToolRegistry.runToolLoop(provider, registry, "sys", "hello");

    // user + assistant
    assertEquals(2, msgs.size());
    assertEquals("user", msgs.get(0).get("role"));
    assertEquals("hello", msgs.get(0).get("content"));
    assertEquals("assistant", msgs.get(1).get("role"));
  }

  @Test
  public void testRunToolLoop_withToolCall() throws Exception {
    java.util.List<String> collected = new java.util.ArrayList<>();
    ToolRegistry registry = new ToolRegistry();
    registry.register(
        ToolDefinition.builder()
            .name("collect")
            .description("d")
            .inputSchema(Collections.singletonMap("type", "object"))
            .build(),
        input -> {
          collected.add((String) input.get("v"));
          return "ok";
        });

    io.temporal.toolregistry.testing.MockProvider provider =
        new io.temporal.toolregistry.testing.MockProvider(
            io.temporal.toolregistry.testing.MockResponse.toolCall(
                "collect", Collections.singletonMap("v", "first")),
            io.temporal.toolregistry.testing.MockResponse.toolCall(
                "collect", Collections.singletonMap("v", "second")),
            io.temporal.toolregistry.testing.MockResponse.done("all done"));
    provider.withRegistry(
        new io.temporal.toolregistry.testing.FakeToolRegistry() {
          {
            register(
                ToolDefinition.builder()
                    .name("collect")
                    .description("d")
                    .inputSchema(Collections.singletonMap("type", "object"))
                    .build(),
                input -> {
                  collected.add((String) input.get("v"));
                  return "ok";
                });
          }
        });

    List<Map<String, Object>> msgs = ToolRegistry.runToolLoop(provider, registry, "sys", "go");

    assertEquals(Arrays.asList("first", "second"), collected);
    // user + (assistant + tool_result_wrapper)*2 + final assistant
    assertTrue(msgs.size() > 4);
  }

  // ── fromMcpTools ──────────────────────────────────────────────────────────────

  @Test
  public void testFromMcpTools_populatesRegistry() throws Exception {
    McpTool t1 =
        new McpTool(
            "read_file",
            "Read a file",
            Map.of(
                "type", "object",
                "properties", Map.of("path", Map.of("type", "string")),
                "required", List.of("path")));
    McpTool t2 = new McpTool("list_dir", null, null); // null schema → empty object schema

    ToolRegistry reg = ToolRegistry.fromMcpTools(Arrays.asList(t1, t2));

    List<ToolDefinition> defs = reg.definitions();
    assertEquals(2, defs.size());
    assertEquals("read_file", defs.get(0).getName());
    assertEquals("Read a file", defs.get(0).getDescription());
    assertEquals("list_dir", defs.get(1).getName());
    assertEquals("object", defs.get(1).getInputSchema().get("type"));
    // no-op handler returns empty string
    assertEquals("", reg.dispatch("read_file", Map.of("path", "/etc/hosts")));
  }

  // ── AnthropicProvider is_error / handler error tests ─────────────────────────

  /**
   * Verifies that when a tool handler throws, the Anthropic tool result carries is_error=true and
   * the turn does not propagate the exception.
   */
  @Test
  public void testAnthropicProvider_HandlerError_SetsIsError() throws Exception {
    // Start a minimal HTTP server to mock the Anthropic API.
    com.sun.net.httpserver.HttpServer server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);

    server.createContext(
        "/",
        exchange -> {
          String body =
              "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                  + "\"content\":[{\"type\":\"tool_use\",\"id\":\"c1\","
                  + "\"name\":\"boom\",\"input\":{}}],"
                  + "\"model\":\"claude-sonnet-4-6\",\"stop_reason\":\"tool_use\","
                  + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}";
          byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          try (java.io.OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
          }
        });
    server.start();

    int port = server.getAddress().getPort();
    String baseUrl = "http://localhost:" + port;

    ToolRegistry registry = new ToolRegistry();
    registry.register(
        ToolDefinition.builder()
            .name("boom")
            .description("d")
            .inputSchema(Collections.singletonMap("type", "object"))
            .build(),
        input -> {
          throw new RuntimeException("intentional failure");
        });

    Provider provider =
        new AnthropicProvider(
            AnthropicConfig.builder().apiKey("test-key").baseUrl(baseUrl).build(),
            registry,
            "sys");

    List<Map<String, Object>> messages = new ArrayList<>();
    messages.add(new java.util.LinkedHashMap<>(Map.of("role", "user", "content", "go")));

    TurnResult result = provider.runTurn(messages, registry.definitions());
    server.stop(0);

    assertFalse(result.isDone());
    assertEquals(2, result.getNewMessages().size());

    Map<String, Object> toolResultMsg = result.getNewMessages().get(1);
    assertEquals("user", toolResultMsg.get("role"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> toolResults =
        (List<Map<String, Object>>) toolResultMsg.get("content");
    assertEquals(1, toolResults.size());
    assertEquals("tool_result", toolResults.get(0).get("type"));
    assertEquals(Boolean.TRUE, toolResults.get(0).get("is_error"));
    String content = (String) toolResults.get(0).get("content");
    assertTrue("error message should contain failure text", content.contains("intentional failure"));
  }

  // ── Integration tests (skipped unless RUN_INTEGRATION_TESTS is set) ───────────

  private static ToolRegistry makeRecordRegistry(List<String> collected) throws Exception {
    ToolRegistry registry = new ToolRegistry();
    registry.register(
        ToolDefinition.builder()
            .name("record")
            .description("Record a value")
            .inputSchema(
                Map.of(
                    "type", "object",
                    "properties", Map.of("value", Map.of("type", "string")),
                    "required", List.of("value")))
            .build(),
        input -> {
          collected.add((String) input.get("value"));
          return "recorded";
        });
    return registry;
  }

  @Test
  public void testIntegration_Anthropic() throws Exception {
    Assume.assumeNotNull(System.getenv("RUN_INTEGRATION_TESTS"));
    String apiKey = System.getenv("ANTHROPIC_API_KEY");
    Assume.assumeNotNull(apiKey);

    List<String> collected = new ArrayList<>();
    ToolRegistry registry = makeRecordRegistry(collected);
    Provider provider =
        new AnthropicProvider(
            AnthropicConfig.builder().apiKey(apiKey).build(),
            registry,
            "You must call record() exactly once with value='hello'.");

    ToolRegistry.runToolLoop(
        provider, registry, "", "Please call the record tool with value='hello'.");

    assertTrue("expected 'hello' in collected", collected.contains("hello"));
  }

  @Test
  public void testIntegration_OpenAI() throws Exception {
    Assume.assumeNotNull(System.getenv("RUN_INTEGRATION_TESTS"));
    String apiKey = System.getenv("OPENAI_API_KEY");
    Assume.assumeNotNull(apiKey);

    List<String> collected = new ArrayList<>();
    ToolRegistry registry = makeRecordRegistry(collected);
    Provider provider =
        new OpenAIProvider(
            OpenAIConfig.builder().apiKey(apiKey).build(),
            registry,
            "You must call record() exactly once with value='hello'.");

    ToolRegistry.runToolLoop(
        provider, registry, "", "Please call the record tool with value='hello'.");

    assertTrue("expected 'hello' in collected", collected.contains("hello"));
  }
}
