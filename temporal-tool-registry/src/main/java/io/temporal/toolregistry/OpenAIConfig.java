package io.temporal.toolregistry;

import com.openai.client.OpenAIClient;

/**
 * Configuration for {@link OpenAIProvider}.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * OpenAIConfig cfg = OpenAIConfig.builder()
 *     .apiKey(System.getenv("OPENAI_API_KEY"))
 *     .model("gpt-4o")
 *     .build();
 * OpenAIProvider provider = new OpenAIProvider(cfg, registry, systemPrompt);
 * }</pre>
 */
public final class OpenAIConfig {

  private final String apiKey;
  private final String model;
  private final String baseUrl;
  private final OpenAIClient client;

  private OpenAIConfig(Builder builder) {
    this.apiKey = builder.apiKey;
    this.model = builder.model;
    this.baseUrl = builder.baseUrl;
    this.client = builder.client;
  }

  /** Returns the API key, or {@code null} if {@link #getClient()} is set. */
  public String getApiKey() {
    return apiKey;
  }

  /** Returns the model name, or {@code null} to use the default ({@code "gpt-4o"}). */
  public String getModel() {
    return model;
  }

  /** Returns the base URL override, or {@code null} to use the default OpenAI endpoint. */
  public String getBaseUrl() {
    return baseUrl;
  }

  /**
   * Returns a pre-constructed client to use instead of building one from {@link #getApiKey()} and
   * {@link #getBaseUrl()}. Useful for testing without real API calls.
   */
  public OpenAIClient getClient() {
    return client;
  }

  /** Returns a new builder. */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link OpenAIConfig}. */
  public static final class Builder {

    private String apiKey;
    private String model;
    private String baseUrl;
    private OpenAIClient client;

    private Builder() {}

    /** Sets the OpenAI API key. Required unless {@link #client(OpenAIClient)} is set. */
    public Builder apiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    /** Sets the model name. Defaults to {@code "gpt-4o"}. */
    public Builder model(String model) {
      this.model = model;
      return this;
    }

    /** Overrides the OpenAI API base URL. */
    public Builder baseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
      return this;
    }

    /**
     * Sets a pre-constructed client. When set, {@link #apiKey} and {@link #baseUrl} are ignored.
     * Useful for testing.
     */
    public Builder client(OpenAIClient client) {
      this.client = client;
      return this;
    }

    /** Builds the config. */
    public OpenAIConfig build() {
      return new OpenAIConfig(this);
    }
  }
}
