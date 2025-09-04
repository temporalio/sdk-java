package io.temporal.spring.boot.autoconfigure.template;

import io.temporal.common.metadata.POJOWorkflowMethodMetadata;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.spring.boot.WorkflowImplementationOptionsCustomizer;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkflowImplementationOptions;
import java.util.List;
import javax.annotation.Nullable;

public class WorkflowImplementationOptionsTemplate {
  private final @Nullable List<TemporalOptionsCustomizer<WorkflowImplementationOptions.Builder>>
      customizers;

  public WorkflowImplementationOptionsTemplate(
      @Nullable
          List<TemporalOptionsCustomizer<WorkflowImplementationOptions.Builder>> customizers) {
    this.customizer = customizers;
  }

  public WorkflowImplementationOptions createWorkflowImplementationOptions(
      Worker worker, Class<?> clazz, POJOWorkflowMethodMetadata workflowMethod) {
    WorkflowImplementationOptions.Builder options = WorkflowImplementationOptions.newBuilder();
    if (customizers != null) {
      for (TemporalOptionsCustomizer<WorkflowImplementationOptions.Builder> customizer :
          customizers) {
        options = customizer.customize(options);
        if (customizer instanceof WorkflowImplementationOptionsCustomizer) {
          options =
              ((WorkflowImplementationOptionsCustomizer) customizer)
                  .customize(options, worker, clazz, workflowMethod);
        }
      }
    }
    return options.build();
  }
}
