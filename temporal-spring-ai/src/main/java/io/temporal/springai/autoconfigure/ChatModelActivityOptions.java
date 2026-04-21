package io.temporal.springai.autoconfigure;

import io.temporal.activity.ActivityOptions;
import io.temporal.springai.model.ActivityChatModel;
import io.temporal.springai.plugin.SpringAiPlugin;
import java.util.Map;

/**
 * Spring-bean wrapper for the per-model {@link ActivityOptions} map consumed by {@link
 * SpringAiPlugin}. Inject this type into your config by declaring a bean that returns an instance
 * of it:
 *
 * <pre>{@code
 * @Bean
 * ChatModelActivityOptions chatModelActivityOptions() {
 *     return new ChatModelActivityOptions(Map.of(
 *         "reasoning", ActivityOptions.newBuilder(ActivityChatModel.defaultActivityOptions())
 *             .setStartToCloseTimeout(Duration.ofMinutes(15))
 *             .build()));
 * }
 * }</pre>
 *
 * <p>The wrapper exists so auto-configuration can inject your options by <em>type</em>, not by bean
 * name. Spring's default behavior for {@code Map<String, T>} injection is to collect every bean of
 * type {@code T} into a map keyed by bean name — which for a generic type like {@code Map<String,
 * ActivityOptions>} would sweep in any unrelated {@link ActivityOptions} bean you have in the
 * context. Having a dedicated wrapper type avoids that collision entirely.
 *
 * <p>Keys are chat-model bean names; values are the full {@link ActivityOptions} to use when {@link
 * ActivityChatModel#forModel(String)} / {@link ActivityChatModel#forDefault()} build the stub for
 * that model. Use {@link ActivityChatModel#defaultActivityOptions()} as the baseline so the
 * plugin's non-retryable-error classification is preserved.
 *
 * @param byModelName per-model-bean-name {@link ActivityOptions} overrides; may be empty
 */
public record ChatModelActivityOptions(Map<String, ActivityOptions> byModelName) {

  public ChatModelActivityOptions {
    byModelName = byModelName == null ? Map.of() : Map.copyOf(byModelName);
  }

  /** Returns an empty registry. */
  public static ChatModelActivityOptions empty() {
    return new ChatModelActivityOptions(Map.of());
  }
}
