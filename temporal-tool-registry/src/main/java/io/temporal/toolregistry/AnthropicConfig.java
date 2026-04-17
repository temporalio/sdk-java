package io.temporal.toolregistry;

import com.anthropic.client.AnthropicClient;

/**
 * Configuration for {@link AnthropicProvider}.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * AnthropicConfig cfg = AnthropicConfig.builder()
 *     .apiKey(System.getenv("ANTHROPIC_API_KEY"))
 *     .model("claude-sonnet-4-6")
 *     .build();
 * AnthropicProvider provider = new AnthropicProvider(cfg, registry, systemPrompt);
 * }</pre>
 */
public final class AnthropicConfig {

  private final String apiKey;
  private final String model;
  private final String baseUrl;
  private final AnthropicClient client;

  private AnthropicConfig(Builder builder) {
    this.apiKey = builder.apiKey;
    this.model = builder.model;
    this.baseUrl = builder.baseUrl;
    this.client = builder.client;
  }

  /** Returns the API key, or {@code null} if {@link #getClient()} is set. */
  public String getApiKey() {
    return apiKey;
  }

  /** Returns the model name, or {@code null} to use the default ({@code "claude-sonnet-4-6"}). */
  public String getModel() {
    return model;
  }

  /** Returns the base URL override, or {@code null} to use the default Anthropic endpoint. */
  public String getBaseUrl() {
    return baseUrl;
  }

  /**
   * Returns a pre-constructed client to use instead of building one from {@link #getApiKey()} and
   * {@link #getBaseUrl()}. Useful for testing without real API calls.
   */
  public AnthropicClient getClient() {
    return client;
  }

  /** Returns a new builder. */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link AnthropicConfig}. */
  public static final class Builder {

    private String apiKey;
    private String model;
    private String baseUrl;
    private AnthropicClient client;

    private Builder() {}

    /** Sets the Anthropic API key. Required unless {@link #client(AnthropicClient)} is set. */
    public Builder apiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    /** Sets the model name. Defaults to {@code "claude-sonnet-4-6"}. */
    public Builder model(String model) {
      this.model = model;
      return this;
    }

    /** Overrides the Anthropic API base URL (e.g. for proxies). */
    public Builder baseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
      return this;
    }

    /**
     * Sets a pre-constructed client. When set, {@link #apiKey} and {@link #baseUrl} are ignored.
     * Useful for testing.
     */
    public Builder client(AnthropicClient client) {
      this.client = client;
      return this;
    }

    /** Builds the config. */
    public AnthropicConfig build() {
      return new AnthropicConfig(this);
    }
  }
}
