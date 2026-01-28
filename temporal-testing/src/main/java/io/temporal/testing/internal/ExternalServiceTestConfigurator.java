package io.temporal.testing.internal;

import io.temporal.internal.common.env.EnvironmentVariableUtils;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowRule;

public class ExternalServiceTestConfigurator {
  private static boolean USE_DOCKER_SERVICE =
      EnvironmentVariableUtils.readBooleanFlag("USE_DOCKER_SERVICE");
  private static String TEMPORAL_SERVICE_ADDRESS =
      EnvironmentVariableUtils.readString("TEMPORAL_SERVICE_ADDRESS");
  private static boolean USE_VIRTUAL_THREADS =
      EnvironmentVariableUtils.readBooleanFlag("USE_VIRTUAL_THREADS");

  public static boolean isUseExternalService() {
    return USE_DOCKER_SERVICE;
  }

  public static boolean isUseVirtualThreads() {
    return USE_VIRTUAL_THREADS;
  }

  public static String getTemporalServiceAddress() {
    return USE_DOCKER_SERVICE
        ? (TEMPORAL_SERVICE_ADDRESS != null ? TEMPORAL_SERVICE_ADDRESS : "127.0.0.1:7233")
        : null;
  }

  public static TestWorkflowRule.Builder configure(TestWorkflowRule.Builder testWorkflowRule) {
    if (USE_DOCKER_SERVICE) {
      testWorkflowRule.setUseExternalService(true);
      if (TEMPORAL_SERVICE_ADDRESS != null) {
        testWorkflowRule.setTarget(TEMPORAL_SERVICE_ADDRESS);
      }
    }
    return testWorkflowRule;
  }

  public static TestEnvironmentOptions.Builder configure(
      TestEnvironmentOptions.Builder testEnvironmentOptions) {
    if (USE_DOCKER_SERVICE) {
      testEnvironmentOptions.setUseExternalService(true);
      if (TEMPORAL_SERVICE_ADDRESS != null) {
        testEnvironmentOptions.setTarget(TEMPORAL_SERVICE_ADDRESS);
      }
    }
    return testEnvironmentOptions;
  }

  public static TestEnvironmentOptions.Builder configuredTestEnvironmentOptions() {
    return configure(TestEnvironmentOptions.newBuilder());
  }
}
