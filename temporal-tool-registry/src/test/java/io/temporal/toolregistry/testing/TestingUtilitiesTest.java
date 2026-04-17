package io.temporal.toolregistry.testing;

import static org.junit.Assert.*;

import io.temporal.toolregistry.ToolDefinition;
import io.temporal.toolregistry.TurnResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** Unit tests for the testing utilities package (no Temporal server or API key needed). */
public class TestingUtilitiesTest {

  // ── MockResponse ──────────────────────────────────────────────────────────────

  @Test
  public void testMockResponse_done_isStop() {
    MockResponse r = MockResponse.done("finished");
    assertTrue(r.isStop());
    assertEquals(1, r.getContent().size());
    assertEquals("text", r.getContent().get(0).get("type"));
    assertEquals("finished", r.getContent().get(0).get("text"));
  }

  @Test
  public void testMockResponse_done_noText() {
    MockResponse r = MockResponse.done();
    assertTrue(r.isStop());
    assertTrue(r.getContent().isEmpty());
  }

  @Test
  public void testMockResponse_toolCall_structure() {
    MockResponse r = MockResponse.toolCall("my_tool", Collections.singletonMap("x", "1"), "id42");
    assertFalse(r.isStop());
    assertEquals(1, r.getContent().size());
    Map<String, Object> block = r.getContent().get(0);
    assertEquals("tool_use", block.get("type"));
    assertEquals("my_tool", block.get("name"));
    assertEquals("id42", block.get("id"));
    assertEquals(Collections.singletonMap("x", "1"), block.get("input"));
  }

  @Test
  public void testMockResponse_toolCall_generatesId() {
    MockResponse r1 = MockResponse.toolCall("t", Collections.emptyMap());
    MockResponse r2 = MockResponse.toolCall("t", Collections.emptyMap());
    String id1 = (String) r1.getContent().get(0).get("id");
    String id2 = (String) r2.getContent().get(0).get("id");
    assertNotNull(id1);
    assertNotNull(id2);
    assertNotEquals(id1, id2);
  }

  // ── MockProvider ─────────────────────────────────────────────────────────────

  @Test
  public void testMockProvider_returnsResponsesInOrder() throws Exception {
    MockProvider provider =
        new MockProvider(MockResponse.done("first"), MockResponse.done("second"));

    List<Map<String, Object>> msgs = new ArrayList<>();
    msgs.add(Map.of("role", "user", "content", "hello"));

    TurnResult t1 = provider.runTurn(msgs, Collections.emptyList());
    assertTrue(t1.isDone());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> content1 =
        (List<Map<String, Object>>) t1.getNewMessages().get(0).get("content");
    assertEquals("first", content1.get(0).get("text"));

    msgs.addAll(t1.getNewMessages());
    TurnResult t2 = provider.runTurn(msgs, Collections.emptyList());
    assertTrue(t2.isDone());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> content2 =
        (List<Map<String, Object>>) t2.getNewMessages().get(0).get("content");
    assertEquals("second", content2.get(0).get("text"));
  }

  @Test
  public void testMockProvider_throwsWhenExhausted() {
    MockProvider provider = new MockProvider(MockResponse.done("only one"));
    List<Map<String, Object>> msgs =
        Collections.singletonList(Map.of("role", "user", "content", "x"));
    try {
      provider.runTurn(msgs, Collections.emptyList());
      provider.runTurn(msgs, Collections.emptyList()); // second call should throw
      fail("expected exception");
    } catch (Exception e) {
      assertTrue(e.getMessage().contains("ran out"));
    }
  }

  @Test
  public void testMockProvider_toolCallDispatches() throws Exception {
    FakeToolRegistry fake = new FakeToolRegistry();
    fake.register(
        ToolDefinition.builder()
            .name("my_tool")
            .description("d")
            .inputSchema(Collections.singletonMap("type", "object"))
            .build(),
        input -> "result-" + input.get("v"));

    MockProvider provider =
        new MockProvider(
                MockResponse.toolCall("my_tool", Collections.singletonMap("v", "42"), "call1"),
                MockResponse.done("done"))
            .withRegistry(fake);

    List<Map<String, Object>> msgs = new ArrayList<>();
    msgs.add(Map.of("role", "user", "content", "go"));

    TurnResult t1 = provider.runTurn(msgs, Collections.emptyList());
    assertFalse(t1.isDone());
    // The turn should produce: assistant message + tool_result wrapper
    assertEquals(2, t1.getNewMessages().size());

    // Verify the tool was dispatched with the right input.
    assertEquals(1, fake.getCalls().size());
    assertEquals("my_tool", fake.getCalls().get(0).getName());
    assertEquals(Collections.singletonMap("v", "42"), fake.getCalls().get(0).getInput());
  }

  // ── FakeToolRegistry ─────────────────────────────────────────────────────────

  @Test
  public void testFakeToolRegistry_recordsCalls() throws Exception {
    FakeToolRegistry fake = new FakeToolRegistry();
    fake.register(
        ToolDefinition.builder()
            .name("fn")
            .description("d")
            .inputSchema(Collections.singletonMap("type", "object"))
            .build(),
        input -> "ok");

    fake.dispatch("fn", Collections.singletonMap("a", "b"));
    fake.dispatch("fn", Collections.singletonMap("a", "c"));

    assertEquals(2, fake.getCalls().size());
    assertEquals("fn", fake.getCalls().get(0).getName());
    assertEquals(Collections.singletonMap("a", "b"), fake.getCalls().get(0).getInput());
    assertEquals(Collections.singletonMap("a", "c"), fake.getCalls().get(1).getInput());
  }

  @Test
  public void testFakeToolRegistry_clearCalls() throws Exception {
    FakeToolRegistry fake = new FakeToolRegistry();
    fake.register(
        ToolDefinition.builder()
            .name("fn")
            .description("d")
            .inputSchema(Collections.singletonMap("type", "object"))
            .build(),
        input -> "ok");
    fake.dispatch("fn", Collections.emptyMap());
    assertEquals(1, fake.getCalls().size());

    fake.clearCalls();
    assertEquals(0, fake.getCalls().size());
  }

  // ── CrashAfterTurns ──────────────────────────────────────────────────────────

  @Test
  public void testCrashAfterTurns_crashesAtRightTime() {
    CrashAfterTurns crasher = new CrashAfterTurns(2);
    List<Map<String, Object>> msgs =
        Collections.singletonList(Map.of("role", "user", "content", "x"));

    try {
      crasher.runTurn(msgs, Collections.emptyList()); // turn 1 — OK
      crasher.runTurn(msgs, Collections.emptyList()); // turn 2 — OK
      crasher.runTurn(msgs, Collections.emptyList()); // turn 3 — crash
      fail("expected exception");
    } catch (Exception e) {
      assertTrue(e.getMessage().contains("crashed after 2"));
    }
  }

  @Test
  public void testCrashAfterTurns_withDelegate() throws Exception {
    MockProvider inner = new MockProvider(MockResponse.done("t1"), MockResponse.done("t2"));
    CrashAfterTurns crasher = new CrashAfterTurns(1, inner);
    List<Map<String, Object>> msgs =
        Collections.singletonList(Map.of("role", "user", "content", "x"));

    TurnResult result = crasher.runTurn(msgs, Collections.emptyList()); // delegates
    assertTrue(result.isDone());

    try {
      crasher.runTurn(msgs, Collections.emptyList()); // crashes
      fail("expected exception");
    } catch (Exception e) {
      assertTrue(e.getMessage().contains("crashed"));
    }
  }

  // ── MockAgenticSession ────────────────────────────────────────────────────────

  @Test
  public void testMockAgenticSession_capturesPrompt() {
    MockAgenticSession mock = new MockAgenticSession();
    mock.runToolLoop(null, null, "sys", "the prompt");
    assertEquals("the prompt", mock.getCapturedPrompt());
  }

  @Test
  public void testMockAgenticSession_preSeedIssues() {
    MockAgenticSession mock = new MockAgenticSession();
    mock.getMutableIssues().add(Collections.singletonMap("desc", "pre-existing"));

    List<Map<String, Object>> issues = mock.getIssues();
    assertEquals(1, issues.size());
    assertEquals("pre-existing", issues.get(0).get("desc"));
  }

  @Test
  public void testMockAgenticSession_doesNotCallProvider() {
    // runToolLoop should be a no-op — no exceptions from null provider.
    MockAgenticSession mock = new MockAgenticSession();
    mock.runToolLoop(null, null, null, "prompt");
    assertEquals("prompt", mock.getCapturedPrompt());
  }

  // ── DispatchCall ─────────────────────────────────────────────────────────────

  @Test
  public void testDispatchCall_getters() {
    Map<String, Object> input = Collections.singletonMap("k", "v");
    DispatchCall call = new DispatchCall("tool_name", input);
    assertEquals("tool_name", call.getName());
    assertEquals(input, call.getInput());
    assertTrue(call.toString().contains("tool_name"));
  }
}
