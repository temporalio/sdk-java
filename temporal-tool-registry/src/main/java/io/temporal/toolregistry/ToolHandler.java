package io.temporal.toolregistry;

import java.util.Map;

/**
 * Called when the LLM invokes a tool. Receives the parsed tool input and returns a string result or
 * throws an exception.
 *
 * <p>This is a functional interface; implementations can be lambdas:
 *
 * <pre>{@code
 * ToolHandler handler = input -> {
 *     issues.add(input.get("description"));
 *     return "recorded";
 * };
 * }</pre>
 */
@FunctionalInterface
public interface ToolHandler {
  String handle(Map<String, Object> input) throws Exception;
}
