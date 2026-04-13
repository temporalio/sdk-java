package io.temporal.toolregistry.testing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A scripted response for {@link MockProvider}.
 *
 * <p>Use the factory methods to create responses:
 *
 * <pre>{@code
 * // A turn that calls a tool
 * MockResponse.toolCall("my_tool", Map.of("param", "value"))
 *
 * // A final turn that stops the loop
 * MockResponse.done("All done.")
 * }</pre>
 */
public final class MockResponse {

  private final boolean stop;
  private final List<Map<String, Object>> content;

  MockResponse(boolean stop, List<Map<String, Object>> content) {
    this.stop = stop;
    this.content = Collections.unmodifiableList(new ArrayList<>(content));
  }

  /** Returns {@code true} if this response signals the loop to stop. */
  public boolean isStop() {
    return stop;
  }

  /** The content blocks for the assistant message. */
  public List<Map<String, Object>> getContent() {
    return content;
  }

  /**
   * Creates a response with a single tool-use block.
   *
   * @param toolName the tool to call
   * @param toolInput the tool input
   */
  public static MockResponse toolCall(String toolName, Map<String, Object> toolInput) {
    return toolCall(toolName, toolInput, UUID.randomUUID().toString());
  }

  /**
   * Creates a response with a single tool-use block and an explicit call ID.
   *
   * @param toolName the tool to call
   * @param toolInput the tool input
   * @param callId the tool_use id
   */
  public static MockResponse toolCall(
      String toolName, Map<String, Object> toolInput, String callId) {
    Map<String, Object> block = new LinkedHashMap<>();
    block.put("type", "tool_use");
    block.put("id", callId);
    block.put("name", toolName);
    block.put("input", toolInput);
    return new MockResponse(false, Collections.singletonList(block));
  }

  /**
   * Creates a final response with optional text content.
   *
   * @param texts zero or more text strings (joined as separate text blocks)
   */
  public static MockResponse done(String... texts) {
    List<Map<String, Object>> blocks = new ArrayList<>();
    for (String text : texts) {
      Map<String, Object> block = new LinkedHashMap<>();
      block.put("type", "text");
      block.put("text", text);
      blocks.add(block);
    }
    return new MockResponse(true, blocks);
  }
}
