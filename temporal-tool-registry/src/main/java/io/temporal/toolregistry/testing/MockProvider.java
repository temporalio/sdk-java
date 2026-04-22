package io.temporal.toolregistry.testing;

import io.temporal.toolregistry.Provider;
import io.temporal.toolregistry.ToolDefinition;
import io.temporal.toolregistry.TurnResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A scripted {@link Provider} that replays pre-configured {@link MockResponse} instances in order.
 *
 * <p>Useful for testing session logic without making real API calls.
 *
 * <p>Example:
 *
 * <pre>{@code
 * MockProvider provider = new MockProvider(
 *     MockResponse.toolCall("flag_issue", Map.of("description", "bug")),
 *     MockResponse.done("Done.")
 * );
 * }</pre>
 *
 * <p>If a {@link FakeToolRegistry} is set via {@link #withRegistry(FakeToolRegistry)}, tool calls
 * are dispatched through it so call history is recorded.
 */
public class MockProvider implements Provider {

  private final List<MockResponse> responses;
  private int index = 0;
  private FakeToolRegistry registry;

  /** Creates a MockProvider with the given scripted responses. */
  public MockProvider(MockResponse... responses) {
    this.responses = new ArrayList<>();
    for (MockResponse r : responses) {
      this.responses.add(r);
    }
  }

  /** Creates a MockProvider with the given scripted responses. */
  public MockProvider(List<MockResponse> responses) {
    this.responses = new ArrayList<>(responses);
  }

  /**
   * Wires up a {@link FakeToolRegistry} so that tool calls from scripted responses are dispatched
   * and recorded.
   *
   * @return {@code this} for chaining
   */
  public MockProvider withRegistry(FakeToolRegistry registry) {
    this.registry = registry;
    return this;
  }

  @Override
  public TurnResult runTurn(List<Map<String, Object>> messages, List<ToolDefinition> tools)
      throws Exception {
    if (index >= responses.size()) {
      throw new IllegalStateException(
          "MockProvider ran out of scripted responses after " + index + " turn(s)");
    }
    MockResponse resp = responses.get(index++);

    List<Map<String, Object>> newMessages = new ArrayList<>();

    // Build the assistant message.
    Map<String, Object> assistantMsg = new LinkedHashMap<>();
    assistantMsg.put("role", "assistant");
    assistantMsg.put("content", resp.getContent());
    newMessages.add(assistantMsg);

    if (resp.isStop()) {
      return new TurnResult(newMessages, true);
    }

    // Dispatch tool calls if a registry is wired up.
    List<Map<String, Object>> toolResults = new ArrayList<>();
    for (Map<String, Object> block : resp.getContent()) {
      if ("tool_use".equals(block.get("type"))) {
        String name = (String) block.get("name");
        String id = (String) block.get("id");
        @SuppressWarnings("unchecked")
        Map<String, Object> input = (Map<String, Object>) block.get("input");

        String result;
        if (registry != null) {
          try {
            result = registry.dispatch(name, input);
          } catch (Exception e) {
            result = "error: " + e.getMessage();
          }
        } else {
          result = "ok";
        }

        Map<String, Object> toolResult = new LinkedHashMap<>();
        toolResult.put("type", "tool_result");
        toolResult.put("tool_use_id", id);
        toolResult.put("content", result);
        toolResults.add(toolResult);
      }
    }

    if (!toolResults.isEmpty()) {
      Map<String, Object> toolResultMsg = new LinkedHashMap<>();
      toolResultMsg.put("role", "user");
      toolResultMsg.put("content", toolResults);
      newMessages.add(toolResultMsg);
    }

    return new TurnResult(newMessages, false);
  }
}
