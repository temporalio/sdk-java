package io.temporal.toolregistry;

import java.util.Map;
import java.util.Objects;

/**
 * MCP-compatible tool descriptor.
 *
 * <p>Any MCP {@code Tool} object can be adapted to this class by copying its {@code name},
 * {@code description}, and {@code inputSchema} fields.
 */
public final class McpTool {

  private final String name;
  private final String description;
  private final Map<String, Object> inputSchema;

  /**
   * Creates an MCP tool descriptor.
   *
   * @param name tool name
   * @param description human-readable description
   * @param inputSchema JSON Schema for the tool's input object (may be {@code null})
   */
  public McpTool(String name, String description, Map<String, Object> inputSchema) {
    this.name = Objects.requireNonNull(name, "name");
    this.description = description != null ? description : "";
    this.inputSchema = inputSchema;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public Map<String, Object> getInputSchema() {
    return inputSchema;
  }
}
