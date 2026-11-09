package io.temporal.envconfig;

import io.temporal.common.Experimental;
import io.temporal.serviceclient.WorkflowServiceStubsPlugin;
import java.io.IOException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * An extension that reads additional config from a loaded profile and the environment, and returns
 * a plugin that applies it to the client. Extensions are passed to {@link
 * ClientConfigProfile#load(LoadClientConfigProfileOptions)} via {@link
 * LoadClientConfigProfileOptions.Builder#setExtensions(java.util.List)}, and any returned plugins
 * are included in {@link ClientConfigProfile#toWorkflowServiceStubsOptions()}.
 *
 * <p>An extension's TOML config lives in the {@code profile.<profile>.ext.<name>} table, available
 * as {@code profile.getExt().get(name)}. Extensions are recommended to use a {@code
 * TEMPORAL_EXT_<NAME>_} prefix for their environment variables.
 *
 * <p>Example extension that sets an API key from its own config:
 *
 * <pre>{@code
 * public class MyExtension implements ClientConfigExtension {
 *   @Override
 *   public String getName() {
 *     return "my_extension";
 *   }
 *
 *   @Override
 *   public WorkflowServiceStubsPlugin getClientPlugin(ClientConfigExtensionRequest request) {
 *     Map<String, Object> config = request.getProfile().getExt().get(getName());
 *     String apiKey = config != null ? (String) config.get("api_key") : null;
 *     if (request.getEnv() != null && request.getEnv().containsKey("TEMPORAL_EXT_MY_EXTENSION_API_KEY")) {
 *       apiKey = request.getEnv().get("TEMPORAL_EXT_MY_EXTENSION_API_KEY");
 *     }
 *     if (apiKey == null) {
 *       return null;
 *     }
 *     String key = apiKey;
 *     return new SimplePlugin("my-org.my-extension") {
 *       @Override
 *       public void configureServiceStubs(WorkflowServiceStubsOptions.Builder builder) {
 *         builder.addApiKey(() -> key);
 *       }
 *     };
 *   }
 * }
 * }</pre>
 *
 * <p>The returned plugin is a {@link WorkflowServiceStubsPlugin} so that it can configure the
 * connection. A plugin that also implements {@link io.temporal.client.WorkflowClientPlugin} (such
 * as {@link io.temporal.common.SimplePlugin}) is propagated to a workflow client created from the
 * resulting service stubs, and from there to workers.
 */
@Experimental
public interface ClientConfigExtension {
  /**
   * The extension's table key under {@code profile.<profile>.ext}. Must be non-empty and unique
   * among loaded extensions.
   */
  @Nonnull
  String getName();

  /**
   * Reads this extension's config from the profile and env, and returns a plugin that applies it to
   * the client.
   *
   * @param request the loaded profile and env
   * @return a plugin, or null if there's nothing to apply
   * @throws IOException if the extension's config cannot be read or applied
   */
  @Nullable
  WorkflowServiceStubsPlugin getClientPlugin(@Nonnull ClientConfigExtensionRequest request)
      throws IOException;
}
