package io.temporal.spring.boot;

import io.temporal.worker.WorkerOptions;
import javax.annotation.Nonnull;

/**
 * Bean of this class can be added to Spring context to get a fine control over WorkerOptions
 * objects that are created by Temporal Spring Boot Autoconfigure module.
 *
 * <p>Only one bean of this or {@code TemporalOptionsCustomizer<WorkerOptions.Builder>} type can be
 * added to Spring context.
 */
public interface WorkerOptionsCustomizer extends TemporalOptionsCustomizer<WorkerOptions.Builder> {
  @Nonnull
  @Override
  default WorkerOptions.Builder customize(@Nonnull WorkerOptions.Builder optionsBuilder) {
    return optionsBuilder;
  }

  /**
   * This method can modify some fields of the provided {@code WorkerOptions.Builder} or create a
   * new builder altogether and return it back to be used. This method is called after the {@code
   * WorkerOptions.Builder} is initialized by the Temporal Spring Boot module, so changes done by
   * this method will override Spring Boot configuration values.
   *
   * @param optionsBuilder {@code *Options.Builder} to customize
   * @param workerName name of a Worker that will be created using the {@link WorkerOptions}
   *     produced by this call
   * @param taskQueue Task Queue of a Worker that will be created using the {@link WorkerOptions}
   *     produced by this call
   * @return modified {@code optionsBuilder} or a new builder instance to be used by the caller code
   */
  @Nonnull
  WorkerOptions.Builder customize(
      @Nonnull WorkerOptions.Builder optionsBuilder,
      @Nonnull String workerName,
      @Nonnull String taskQueue);
}
