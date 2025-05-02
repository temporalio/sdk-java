package io.temporal.spring.boot;

import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.WorkflowImplementationOptions;
import javax.annotation.Nonnull;

/**
 * Beans of this class can be added to Spring context to get a fine control over *Options objects
 * that are created by Temporal Spring Boot Autoconfigure module.
 *
 * <p>Only one bean of each generic type can be added to Spring context.
 *
 * @param <T> Temporal Options Builder to customize. Respected types: {@link
 *     WorkflowServiceStubsOptions.Builder}, {@link WorkflowClientOptions.Builder}, {@link
 *     TestEnvironmentOptions.Builder}, {@link WorkerOptions.Builder}, {@link
 *     WorkerFactoryOptions.Builder}, {@link WorkflowImplementationOptions.Builder}
 * @see WorkerOptionsCustomizer to get access to a name and task queue of a worker that's being
 *     customized
 */
public interface TemporalOptionsCustomizer<T> {
  /**
   * This method can modify some fields of the provided builder or create a new builder altogether
   * and return it back to be used. This method is called after the {@code *Options.Builder} is
   * initialized by the Temporal Spring Boot module, so changes done by this method will override
   * Spring Boot configuration values.
   *
   * @param optionsBuilder {@code *Options.Builder} to customize
   * @return modified {@code optionsBuilder} or a new builder to be used by the caller code.
   */
  @Nonnull
  T customize(@Nonnull T optionsBuilder);
}
