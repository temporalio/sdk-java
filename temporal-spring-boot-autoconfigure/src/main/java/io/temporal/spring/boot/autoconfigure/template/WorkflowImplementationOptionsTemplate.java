package io.temporal.spring.boot.autoconfigure.template;

import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.worker.WorkflowImplementationOptions;
import javax.annotation.Nullable;

public class WorkflowImplementationOptionsTemplate {
  private final @Nullable TemporalOptionsCustomizer<WorkflowImplementationOptions.Builder>
      customizer;

  public WorkflowImplementationOptionsTemplate(
      @Nullable TemporalOptionsCustomizer<WorkflowImplementationOptions.Builder> customizer) {
    this.customizer = customizer;
  }

  public WorkflowImplementationOptions createWorkflowImplementationOptions() {
    WorkflowImplementationOptions.Builder options = WorkflowImplementationOptions.newBuilder();

    if (customizer != null) {
      options = customizer.customize(options);
    }

    return options.build();
  }
}
