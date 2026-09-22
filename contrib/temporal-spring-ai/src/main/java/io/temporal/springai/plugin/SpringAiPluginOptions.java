package io.temporal.springai.plugin;

import io.temporal.activity.ActivityOptions;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-scoped registry of per-chat-model {@link ActivityOptions}, populated by {@link
 * SpringAiPlugin} at worker construction and consulted by {@link
 * io.temporal.springai.model.ActivityChatModel#forModel(String)} when building the activity stub.
 *
 * <p>The registry is a static singleton because the plugin is a worker-side object but the lookup
 * happens in workflow code that runs on the same JVM. Populating a shared static map before any
 * workflow executes is the cleanest way to bridge that without teaching workflow code about plugin
 * instances.
 *
 * <p>Limitations:
 *
 * <ul>
 *   <li>Only one set of per-model options per JVM. Running multiple plugins in the same worker
 *       process with different per-model options is not supported — the last registration wins.
 *   <li>Callers who invoke {@link io.temporal.springai.model.ActivityChatModel#forModel(String,
 *       ActivityOptions)} or {@link
 *       io.temporal.springai.model.ActivityChatModel#forDefault(ActivityOptions)} bypass the
 *       registry — explicit options always win.
 * </ul>
 */
public final class SpringAiPluginOptions {

  private static final AtomicReference<Map<String, ActivityOptions>> REGISTRY =
      new AtomicReference<>(Map.of());

  private SpringAiPluginOptions() {}

  /**
   * Installs the given per-model-name {@link ActivityOptions}, replacing any previous entries.
   * Called by {@link SpringAiPlugin}. A null or empty map clears the registry.
   */
  public static void register(Map<String, ActivityOptions> options) {
    REGISTRY.set(options == null || options.isEmpty() ? Map.of() : Map.copyOf(options));
  }

  /**
   * Returns the options registered for the given model name, or empty if none. A null {@code
   * modelName} always returns empty.
   */
  public static Optional<ActivityOptions> optionsFor(String modelName) {
    if (modelName == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(REGISTRY.get().get(modelName));
  }
}
