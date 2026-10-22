package io.temporal.client;

import io.temporal.common.Experimental;
import javax.annotation.Nonnull;

/**
 * Plugin interface for customizing Temporal activity client configuration.
 *
 * <p>Plugins that implement both {@link io.temporal.serviceclient.WorkflowServiceStubsPlugin} and
 * {@code ActivityClientPlugin} are automatically propagated from the service stubs to the activity
 * client.
 *
 * @see io.temporal.serviceclient.WorkflowServiceStubsPlugin
 */
@Experimental
public interface ActivityClientPlugin {

  /**
   * Returns a unique name for this plugin. Used for logging and duplicate detection. Recommended
   * format: "organization.plugin-name" (e.g., "io.temporal.tracing")
   *
   * @return fully qualified plugin name
   */
  @Nonnull
  String getName();

  /**
   * Allows the plugin to modify activity client options before the client is created. Called during
   * configuration phase in forward (registration) order.
   *
   * @param builder the options builder to modify
   */
  void configureActivityClient(@Nonnull ActivityClientOptions.Builder builder);
}
