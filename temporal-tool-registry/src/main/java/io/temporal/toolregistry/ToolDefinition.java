package io.temporal.toolregistry;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Defines an LLM tool in Anthropic's tool_use JSON format.
 *
 * <p>The same definition is used for both Anthropic and OpenAI; each {@link Provider} converts the
 * schema to the wire format it needs.
 *
 * <p>Example:
 *
 * <pre>{@code
 * ToolDefinition def = ToolDefinition.builder()
 *     .name("flag_issue")
 *     .description("Flag a problem found during analysis")
 *     .inputSchema(Map.of(
 *         "type", "object",
 *         "properties", Map.of("description", Map.of("type", "string")),
 *         "required", List.of("description")))
 *     .build();
 * }</pre>
 */
public final class ToolDefinition {

  private final String name;
  private final String description;

  @JsonProperty("input_schema")
  private final Map<String, Object> inputSchema;

  private ToolDefinition(Builder builder) {
    this.name = Objects.requireNonNull(builder.name, "name");
    this.description = Objects.requireNonNull(builder.description, "description");
    this.inputSchema =
        Collections.unmodifiableMap(Objects.requireNonNull(builder.inputSchema, "inputSchema"));
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  @JsonProperty("input_schema")
  public Map<String, Object> getInputSchema() {
    return inputSchema;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String name;
    private String description;
    private Map<String, Object> inputSchema;

    private Builder() {}

    public Builder name(String name) {
      this.name = name;
      return this;
    }

    public Builder description(String description) {
      this.description = description;
      return this;
    }

    public Builder inputSchema(Map<String, Object> inputSchema) {
      this.inputSchema = inputSchema;
      return this;
    }

    public ToolDefinition build() {
      return new ToolDefinition(this);
    }
  }
}
