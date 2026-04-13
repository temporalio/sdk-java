package io.temporal.toolregistry;

import static org.junit.Assert.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.testing.TestActivityEnvironment;
import io.temporal.toolregistry.testing.FakeToolRegistry;
import io.temporal.toolregistry.testing.MockProvider;
import io.temporal.toolregistry.testing.MockResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Tests for {@link AgenticSession}: runToolLoop, checkpoint restore via {@link
 * AgenticSession#runWithSession}.
 *
 * <p>All session tests execute inside a {@link TestActivityEnvironment} so that {@link
 * io.temporal.activity.Activity#getExecutionContext()} is available.
 */
public class AgenticSessionTest {

  // ── test harness ──────────────────────────────────────────────────────────────

  @ActivityInterface
  public interface TestOp {
    @ActivityMethod
    void execute();
  }

  /**
   * Runs {@code task} inside a real activity context provided by {@link TestActivityEnvironment}.
   */
  private static void runInActivity(Runnable task) {
    TestActivityEnvironment env = TestActivityEnvironment.newInstance();
    try {
      env.registerActivitiesImplementations(
          new TestOp() {
            @Override
            public void execute() {
              task.run();
            }
          });
      env.newActivityStub(TestOp.class).execute();
    } finally {
      env.close();
    }
  }

  // ── runToolLoop ───────────────────────────────────────────────────────────────

  @Test
  public void testFreshStart() {
    MockProvider provider = new MockProvider(MockResponse.done("finished"));
    ToolRegistry registry = new ToolRegistry();
    List<Map<String, Object>> captured = new ArrayList<>();

    runInActivity(
        () -> {
          AgenticSession session = new AgenticSession();
          try {
            session.runToolLoop(provider, registry, "sys", "my prompt");
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
          captured.addAll(session.getMessages());
        });

    assertEquals("user", captured.get(0).get("role"));
    assertEquals("my prompt", captured.get(0).get("content"));
    assertEquals("assistant", captured.get(1).get("role"));
  }

  @Test
  public void testResumesExistingMessages() {
    // When messages is already populated (retry case), the prompt is NOT prepended again.
    MockProvider provider = new MockProvider(MockResponse.done("ok"));
    ToolRegistry registry = new ToolRegistry();
    List<Map<String, Object>> captured = new ArrayList<>();

    runInActivity(
        () -> {
          AgenticSession session = new AgenticSession();
          // Simulate a partially-restored session.
          session.restore(
              new SessionCheckpoint(
                  Arrays.asList(
                      Map.of("role", "user", "content", "original"),
                      Map.of(
                          "role",
                          "assistant",
                          "content",
                          Collections.singletonList(Map.of("type", "text", "text", "thinking")))),
                  new ArrayList<>()));
          try {
            session.runToolLoop(provider, registry, "sys", "ignored prompt");
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
          captured.addAll(session.getMessages());
        });

    assertEquals("original", captured.get(0).get("content"));
    assertEquals("assistant", captured.get(1).get("role"));
  }

  @Test
  public void testWithToolCalls() {
    List<String> collected = new ArrayList<>();
    FakeToolRegistry fakeRegistry = new FakeToolRegistry();
    fakeRegistry.register(
        ToolDefinition.builder()
            .name("collect")
            .description("d")
            .inputSchema(Collections.singletonMap("type", "object"))
            .build(),
        input -> {
          collected.add((String) input.get("v"));
          return "ok";
        });

    MockProvider provider =
        new MockProvider(
                MockResponse.toolCall("collect", Collections.singletonMap("v", "first")),
                MockResponse.toolCall("collect", Collections.singletonMap("v", "second")),
                MockResponse.done("done"))
            .withRegistry(fakeRegistry);

    List<Map<String, Object>> captured = new ArrayList<>();
    runInActivity(
        () -> {
          AgenticSession session = new AgenticSession();
          try {
            session.runToolLoop(provider, fakeRegistry, "sys", "go");
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
          captured.addAll(session.getMessages());
        });

    assertEquals(Arrays.asList("first", "second"), collected);
    // user + (assistant + tool_result_wrapper)*2 + final assistant
    assertTrue(captured.size() > 4);
  }

  @Test
  public void testCheckpointOnEachTurn() {
    // Verifies runToolLoop heartbeats inside an activity context without error.
    ToolRegistry registry = new ToolRegistry();
    MockProvider provider = new MockProvider(MockResponse.done("turn1"));
    List<Map<String, Object>> captured = new ArrayList<>();

    runInActivity(
        () -> {
          AgenticSession session = new AgenticSession();
          try {
            session.runToolLoop(provider, registry, "sys", "prompt");
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
          captured.addAll(session.getMessages());
        });

    assertFalse(captured.isEmpty());
  }

  // ── runWithSession ────────────────────────────────────────────────────────────

  @Test
  public void testRunWithSession_freshStart() {
    MockProvider provider = new MockProvider(MockResponse.done("done"));
    ToolRegistry registry = new ToolRegistry();
    List<Map<String, Object>> capturedMessages = new ArrayList<>();

    runInActivity(
        () -> {
          try {
            AgenticSession.runWithSession(
                session -> {
                  session.runToolLoop(provider, registry, "sys", "hello");
                  capturedMessages.addAll(session.getMessages());
                });
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });

    assertFalse(capturedMessages.isEmpty());
    assertEquals("hello", capturedMessages.get(0).get("content"));
  }

  @Test
  public void testRunWithSession_restoreFromCheckpoint() {
    // Pre-seed a checkpoint so the session is restored on first call.
    MockProvider provider = new MockProvider(MockResponse.done("done"));
    ToolRegistry registry = new ToolRegistry();
    List<Map<String, Object>> capturedMessages = new ArrayList<>();

    SessionCheckpoint checkpoint = new SessionCheckpoint();
    checkpoint.messages.add(Map.of("role", "user", "content", "restored prompt"));

    TestActivityEnvironment env = TestActivityEnvironment.newInstance();
    try {
      env.setHeartbeatDetails(checkpoint);
      env.registerActivitiesImplementations(
          new TestOp() {
            @Override
            public void execute() {
              try {
                AgenticSession.runWithSession(
                    session -> {
                      session.runToolLoop(provider, registry, "sys", "ignored");
                      capturedMessages.addAll(session.getMessages());
                    });
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            }
          });
      env.newActivityStub(TestOp.class).execute();
    } finally {
      env.close();
    }

    assertFalse(capturedMessages.isEmpty());
    // The restored message should be the first — not "ignored".
    assertEquals("restored prompt", capturedMessages.get(0).get("content"));
  }

  // ── Checkpoint round-trip test (T6) ──────────────────────────────────────────

  /**
   * Verifies that a SessionCheckpoint with nested tool_calls survives a Jackson JSON
   * serialize/deserialize cycle with all fields intact. Guards against the class of bug where
   * nested maps lose their type after deserialization (cf. .NET List&lt;object?&gt; bug).
   */
  @Test
  public void testCheckpoint_RoundTrip() throws Exception {
    ObjectMapper mapper = new ObjectMapper();

    Map<String, Object> fnMap = new LinkedHashMap<>();
    fnMap.put("name", "my_tool");
    fnMap.put("arguments", "{\"x\":1}");

    Map<String, Object> toolCall = new LinkedHashMap<>();
    toolCall.put("id", "call_abc");
    toolCall.put("type", "function");
    toolCall.put("function", fnMap);

    Map<String, Object> assistantMsg = new LinkedHashMap<>();
    assistantMsg.put("role", "assistant");
    assistantMsg.put("tool_calls", Collections.singletonList(toolCall));

    Map<String, Object> issue = new LinkedHashMap<>();
    issue.put("type", "smell");
    issue.put("file", "Foo.java");

    SessionCheckpoint original = new SessionCheckpoint(
        Collections.singletonList(assistantMsg),
        Collections.singletonList(issue));

    // Simulate Temporal heartbeat round-trip via Jackson.
    String json = mapper.writeValueAsString(original);
    SessionCheckpoint restored = mapper.readValue(json, SessionCheckpoint.class);

    assertEquals(1, restored.messages.size());
    assertEquals("assistant", restored.messages.get(0).get("role"));

    // tool_calls must survive as a list of maps after round-trip.
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> toolCallsRestored =
        (List<Map<String, Object>>) restored.messages.get(0).get("tool_calls");
    assertNotNull(toolCallsRestored);
    assertEquals(1, toolCallsRestored.size());
    assertEquals("call_abc", toolCallsRestored.get(0).get("id"));

    @SuppressWarnings("unchecked")
    Map<String, Object> fnRestored =
        (Map<String, Object>) toolCallsRestored.get(0).get("function");
    assertEquals("my_tool", fnRestored.get("name"));

    assertEquals(1, restored.issues.size());
    assertEquals("smell", restored.issues.get(0).get("type"));
    assertEquals("Foo.java", restored.issues.get(0).get("file"));
  }
}
