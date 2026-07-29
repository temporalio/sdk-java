package io.temporal.testing.internal;

import io.temporal.client.WorkflowClientOptions;
import io.temporal.internal.common.env.EnvironmentVariableUtils;
import io.temporal.internal.docker.RegisterTestNamespace;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowRule;
import javax.annotation.Nullable;

public class ExternalServiceTestConfigurator {
  private static boolean USE_EXTERNAL_SERVICE =
      EnvironmentVariableUtils.readBooleanFlag("USE_EXTERNAL_SERVICE");
  private static String TEMPORAL_SERVICE_ADDRESS =
      EnvironmentVariableUtils.readString("TEMPORAL_SERVICE_ADDRESS");
  private static String TEMPORAL_NAMESPACE =
      EnvironmentVariableUtils.readString("TEMPORAL_NAMESPACE");
  private static String TEMPORAL_CLIENT_CLOUD_API_KEY =
      EnvironmentVariableUtils.readString("TEMPORAL_CLIENT_CLOUD_API_KEY");
  private static boolean USE_VIRTUAL_THREADS =
      EnvironmentVariableUtils.readBooleanFlag("USE_VIRTUAL_THREADS");

  public static boolean isUseExternalService() {
    return USE_EXTERNAL_SERVICE;
  }

  public static boolean isUseVirtualThreads() {
    return USE_VIRTUAL_THREADS;
  }

  public static String getTemporalServiceAddress() {
    return USE_EXTERNAL_SERVICE
        ? (TEMPORAL_SERVICE_ADDRESS != null ? TEMPORAL_SERVICE_ADDRESS : "127.0.0.1:7233")
        : null;
  }

  public static String getNamespace() {
    return TEMPORAL_NAMESPACE != null ? TEMPORAL_NAMESPACE : RegisterTestNamespace.NAMESPACE;
  }

  public static WorkflowServiceStubsOptions getWorkflowServiceStubsOptions() {
    return configureWorkflowServiceStubsOptions(null);
  }

  public static TestWorkflowRule.Builder configure(TestWorkflowRule.Builder testWorkflowRule) {
    return configure(testWorkflowRule, null);
  }

  public static TestWorkflowRule.Builder configure(
      TestWorkflowRule.Builder testWorkflowRule,
      @Nullable WorkflowServiceStubsOptions workflowServiceStubsOptions) {
    if (USE_EXTERNAL_SERVICE) {
      testWorkflowRule.setUseExternalService(true);
      if (TEMPORAL_SERVICE_ADDRESS != null) {
        testWorkflowRule.setTarget(TEMPORAL_SERVICE_ADDRESS);
      }
      if (TEMPORAL_NAMESPACE != null) {
        testWorkflowRule.setNamespace(TEMPORAL_NAMESPACE);
      }
      if (hasApiKey()) {
        testWorkflowRule.setWorkflowServiceStubsOptions(
            configureWorkflowServiceStubsOptions(workflowServiceStubsOptions));
      }
    }
    return testWorkflowRule;
  }

  public static TestEnvironmentOptions.Builder configure(
      TestEnvironmentOptions.Builder testEnvironmentOptions) {
    if (USE_EXTERNAL_SERVICE) {
      TestEnvironmentOptions existingOptions = testEnvironmentOptions.build();
      testEnvironmentOptions.setUseExternalService(true);
      if (TEMPORAL_SERVICE_ADDRESS != null) {
        testEnvironmentOptions.setTarget(TEMPORAL_SERVICE_ADDRESS);
      }
      if (TEMPORAL_NAMESPACE != null) {
        WorkflowClientOptions workflowClientOptions = existingOptions.getWorkflowClientOptions();
        testEnvironmentOptions.setWorkflowClientOptions(
            (workflowClientOptions == null
                    ? WorkflowClientOptions.newBuilder()
                    : WorkflowClientOptions.newBuilder(workflowClientOptions))
                .setNamespace(TEMPORAL_NAMESPACE)
                .build());
      }
      if (hasApiKey()) {
        testEnvironmentOptions.setWorkflowServiceStubsOptions(
            configureWorkflowServiceStubsOptions(existingOptions.getWorkflowServiceStubsOptions()));
      }
    }
    return testEnvironmentOptions;
  }

  public static TestEnvironmentOptions.Builder configuredTestEnvironmentOptions() {
    return configure(TestEnvironmentOptions.newBuilder());
  }

  private static WorkflowServiceStubsOptions configureWorkflowServiceStubsOptions(
      @Nullable WorkflowServiceStubsOptions workflowServiceStubsOptions) {
    WorkflowServiceStubsOptions.Builder builder =
        workflowServiceStubsOptions == null
            ? WorkflowServiceStubsOptions.newBuilder()
            : WorkflowServiceStubsOptions.newBuilder(workflowServiceStubsOptions);
    if (USE_EXTERNAL_SERVICE && TEMPORAL_SERVICE_ADDRESS != null) {
      builder.setTarget(TEMPORAL_SERVICE_ADDRESS);
    }
    if (USE_EXTERNAL_SERVICE && hasApiKey()) {
      builder.addApiKey(() -> TEMPORAL_CLIENT_CLOUD_API_KEY);
    }
    return builder.build();
  }

  private static boolean hasApiKey() {
    return TEMPORAL_CLIENT_CLOUD_API_KEY != null && !TEMPORAL_CLIENT_CLOUD_API_KEY.isEmpty();
  }
}
