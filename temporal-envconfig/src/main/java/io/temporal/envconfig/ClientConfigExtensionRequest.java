package io.temporal.envconfig;

import io.temporal.common.Experimental;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Request for {@link ClientConfigExtension#getClientPlugin(ClientConfigExtensionRequest)}. */
@Experimental
public final class ClientConfigExtensionRequest {
  /** Create a builder for {@link ClientConfigExtensionRequest}. */
  public static Builder newBuilder() {
    return new Builder();
  }

  private final ClientConfigProfile profile;
  private final Map<String, String> env;

  private ClientConfigExtensionRequest(ClientConfigProfile profile, Map<String, String> env) {
    this.profile = profile;
    this.env = env;
  }

  /** Profile that was loaded, after env vars have been applied (unless disabled). */
  @Nonnull
  public ClientConfigProfile getProfile() {
    return profile;
  }

  /**
   * Env to read extension env vars from, or null if env loading is disabled. This is the env
   * overrides map if set, otherwise the process environment. Unmodifiable.
   */
  @Nullable
  public Map<String, String> getEnv() {
    return env;
  }

  public static final class Builder {
    private ClientConfigProfile profile;
    private Map<String, String> env;

    private Builder() {}

    /** Sets the loaded profile. Required. */
    public Builder setProfile(@Nonnull ClientConfigProfile profile) {
      this.profile = profile;
      return this;
    }

    /** Sets the env to read extension env vars from, or null if env loading is disabled. */
    public Builder setEnv(@Nullable Map<String, String> env) {
      this.env = env;
      return this;
    }

    public ClientConfigExtensionRequest build() {
      return new ClientConfigExtensionRequest(
          Objects.requireNonNull(profile, "profile"),
          env == null ? null : Collections.unmodifiableMap(new HashMap<>(env)));
    }
  }
}
