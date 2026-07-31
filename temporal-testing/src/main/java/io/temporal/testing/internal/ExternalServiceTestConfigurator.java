package io.temporal.testing.internal;

import io.temporal.internal.common.env.EnvironmentVariableUtils;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowRule;
import io.temporal.testing.internal.devserver.SdkJavaTestServerProfile;

public class ExternalServiceTestConfigurator {
  private static boolean USE_EXTERNAL_SERVICE =
      EnvironmentVariableUtils.readBooleanFlag("USE_EXTERNAL_SERVICE");
  private static String TEMPORAL_SERVICE_ADDRESS =
      EnvironmentVariableUtils.readString("TEMPORAL_SERVICE_ADDRESS");
  private static boolean USE_VIRTUAL_THREADS =
      EnvironmentVariableUtils.readBooleanFlag("USE_VIRTUAL_THREADS");

  public static boolean isUseExternalService() {
    return USE_EXTERNAL_SERVICE || SdkJavaTestServerProfile.isActive();
  }

  public static boolean isUseVirtualThreads() {
    return USE_VIRTUAL_THREADS;
  }

  public static String getTemporalServiceAddress() {
    if (SdkJavaTestServerProfile.isActive()) {
      return SdkJavaTestServerProfile.getTarget();
    }
    return USE_EXTERNAL_SERVICE
        ? (TEMPORAL_SERVICE_ADDRESS != null ? TEMPORAL_SERVICE_ADDRESS : "127.0.0.1:7233")
        : null;
  }

  public static TestWorkflowRule.Builder configure(TestWorkflowRule.Builder testWorkflowRule) {
    if (isUseExternalService()) {
      testWorkflowRule.setUseExternalService(true);
      String target = getTemporalServiceAddress();
      if (target != null) {
        testWorkflowRule.setTarget(target);
      }
    }
    return testWorkflowRule;
  }

  public static TestEnvironmentOptions.Builder configure(
      TestEnvironmentOptions.Builder testEnvironmentOptions) {
    if (isUseExternalService()) {
      testEnvironmentOptions.setUseExternalService(true);
      String target = getTemporalServiceAddress();
      if (target != null) {
        testEnvironmentOptions.setTarget(target);
      }
    }
    return testEnvironmentOptions;
  }

  public static TestEnvironmentOptions.Builder configuredTestEnvironmentOptions() {
    return configure(TestEnvironmentOptions.newBuilder());
  }
}
