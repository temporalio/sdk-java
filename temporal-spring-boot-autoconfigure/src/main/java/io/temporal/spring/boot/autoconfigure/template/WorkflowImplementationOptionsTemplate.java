package io.temporal.spring.boot.autoconfigure.template;

import io.temporal.common.metadata.POJOWorkflowMethodMetadata;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.spring.boot.WorkflowImplementationOptionsCustomizer;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkflowImplementationOptions;
import javax.annotation.Nullable;

public class WorkflowImplementationOptionsTemplate {
  private final @Nullable TemporalOptionsCustomizer<WorkflowImplementationOptions.Builder>
      customizer;

  public WorkflowImplementationOptionsTemplate(
      @Nullable TemporalOptionsCustomizer<WorkflowImplementationOptions.Builder> customizer) {
    this.customizer = customizer;
  }

  public WorkflowImplementationOptions createWorkflowImplementationOptions(
      Worker worker, Class<?> clazz, POJOWorkflowMethodMetadata workflowMethod) {
    WorkflowImplementationOptions.Builder options = WorkflowImplementationOptions.newBuilder();

    if (customizer != null) {
      options = customizer.customize(options);
      if (customizer instanceof WorkflowImplementationOptionsCustomizer) {
        options =
            ((WorkflowImplementationOptionsCustomizer) customizer)
                .customize(options, worker, clazz, workflowMethod);
      }
    }

    return options.build();
  }
}
