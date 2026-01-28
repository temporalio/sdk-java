package io.temporal.spring.boot;

import io.temporal.common.metadata.POJOWorkflowMethodMetadata;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkflowImplementationOptions;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Bean of this class can be added to Spring context to get a fine control over {@link
 * WorkflowImplementationOptions} objects that are created by Temporal Spring Boot Autoconfigure
 * module.
 *
 * <p>Only one bean of this or {@code
 * TemporalOptionsCustomizer<WorkflowImplementationOptions.Builder>} type can be added to Spring
 * context.
 */
public interface WorkflowImplementationOptionsCustomizer
    extends TemporalOptionsCustomizer<WorkflowImplementationOptions.Builder> {

  @Nonnull
  @Override
  default WorkflowImplementationOptions.Builder customize(
      @Nonnull WorkflowImplementationOptions.Builder optionsBuilder) {
    return optionsBuilder;
  }

  /**
   * This method can modify some fields of the provided {@code
   * WorkflowImplementationOptions.Builder} or create a new builder altogether and return it back to
   * be used. This method is called after the {@code WorkflowImplementationOptions.Builder} is
   * initialized by the Temporal Spring Boot module, so changes done by this method will override
   * Spring Boot configuration values.
   *
   * @param optionsBuilder {@code *Options.Builder} to customize
   * @param worker the worker that the workflow implementation is being registered to.
   * @param clazz the class of the workflow implementation that is being registered.
   * @param workflowMethod the metadata of the workflow method on the interface that is being
   *     registered. null if the class is a {@link io.temporal.workflow.DynamicWorkflow}.
   * @return modified {@code optionsBuilder} or a new builder instance to be used by the caller code
   */
  @Nonnull
  WorkflowImplementationOptions.Builder customize(
      @Nonnull WorkflowImplementationOptions.Builder optionsBuilder,
      @Nonnull Worker worker,
      @Nonnull Class<?> clazz,
      @Nullable POJOWorkflowMethodMetadata workflowMethod);
}
