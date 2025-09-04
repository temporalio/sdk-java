package io.temporal.spring.boot.autoconfigure.template;

import com.uber.m3.tally.Scope;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.spring.boot.autoconfigure.properties.ConnectionProperties;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class ServiceStubsTemplate {
  private final @Nonnull ConnectionProperties connectionProperties;
  private final @Nullable Scope metricsScope;

  // if not null, we work with an environment with defined test server
  private final @Nullable TestWorkflowEnvironmentAdapter testWorkflowEnvironment;

  private final @Nullable List<TemporalOptionsCustomizer<WorkflowServiceStubsOptions.Builder>>
      workflowServiceStubsCustomizers;

  private WorkflowServiceStubs workflowServiceStubs;

  public ServiceStubsTemplate(
      @Nonnull ConnectionProperties connectionProperties,
      @Nullable Scope metricsScope,
      @Nullable TestWorkflowEnvironmentAdapter testWorkflowEnvironment,
      @Nullable
          List<TemporalOptionsCustomizer<WorkflowServiceStubsOptions.Builder>>
              workflowServiceStubsCustomizers) {
    this.connectionProperties = connectionProperties;
    this.metricsScope = metricsScope;
    this.testWorkflowEnvironment = testWorkflowEnvironment;
    this.workflowServiceStubsCustomizers = workflowServiceStubsCustomizers;
  }

  public WorkflowServiceStubs getWorkflowServiceStubs() {
    if (workflowServiceStubs == null) {
      this.workflowServiceStubs = createServiceStubs();
    }
    return workflowServiceStubs;
  }

  private WorkflowServiceStubs createServiceStubs() {
    WorkflowServiceStubs workflowServiceStubs;
    if (testWorkflowEnvironment != null) {
      workflowServiceStubs = testWorkflowEnvironment.getWorkflowClient().getWorkflowServiceStubs();
    } else {
      switch (connectionProperties.getTarget().toLowerCase()) {
        case ConnectionProperties.TARGET_LOCAL_SERVICE:
          workflowServiceStubs = WorkflowServiceStubs.newLocalServiceStubs();
          break;
        default:
          workflowServiceStubs =
              WorkflowServiceStubs.newServiceStubs(
                  new ServiceStubOptionsTemplate(
                          connectionProperties, metricsScope, workflowServiceStubsCustomizers)
                      .createServiceStubOptions());
      }
    }

    return workflowServiceStubs;
  }
}
