package io.temporal.testing.internal;

import io.temporal.envconfig.ClientConfigProfile;
import io.temporal.internal.common.env.EnvironmentVariableUtils;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowRule;
import io.temporal.testing.internal.devserver.SdkJavaTestServerProfile;
import java.io.IOException;
import javax.annotation.Nonnull;

public class ExternalServiceTestConfigurator {
  private static boolean USE_ENV_CONFIG =
      EnvironmentVariableUtils.readBooleanFlag("TEMPORAL_TEST_ENV_CONFIG_SERVER");
  private static boolean USE_EXTERNAL_SERVICE =
      EnvironmentVariableUtils.readBooleanFlag("USE_EXTERNAL_SERVICE");
  private static String TEMPORAL_SERVICE_ADDRESS =
      EnvironmentVariableUtils.readString("TEMPORAL_SERVICE_ADDRESS");
  private static boolean USE_VIRTUAL_THREADS =
      EnvironmentVariableUtils.readBooleanFlag("USE_VIRTUAL_THREADS");

  public static boolean isUseExternalService() {
    return USE_ENV_CONFIG || USE_EXTERNAL_SERVICE || SdkJavaTestServerProfile.isActive();
  }

  public static boolean isUseVirtualThreads() {
    return USE_VIRTUAL_THREADS;
  }

  public static String getTemporalServiceAddress() {
    if (USE_ENV_CONFIG) {
      return loadEnvConfigProfile().getAddress();
    }
    if (SdkJavaTestServerProfile.isActive()) {
      return SdkJavaTestServerProfile.getTarget();
    }
    return USE_EXTERNAL_SERVICE
        ? (TEMPORAL_SERVICE_ADDRESS != null ? TEMPORAL_SERVICE_ADDRESS : "127.0.0.1:7233")
        : null;
  }

  public static TestWorkflowRule.Builder configure(
      @Nonnull TestWorkflowRule.Builder testWorkflowRule) {
    if (USE_ENV_CONFIG) {
      return configureFromEnvConfig(testWorkflowRule, loadEnvConfigProfile());
    }
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
      @Nonnull TestEnvironmentOptions.Builder testEnvironmentOptions) {
    if (USE_ENV_CONFIG) {
      return configureFromEnvConfig(testEnvironmentOptions, loadEnvConfigProfile());
    }
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

  static TestWorkflowRule.Builder configureFromEnvConfig(
      TestWorkflowRule.Builder testWorkflowRule, ClientConfigProfile profile) {
    validateEnvConfigProfile(profile);
    testWorkflowRule.setUseExternalService(true);
    testWorkflowRule.setTarget(profile.getAddress());
    testWorkflowRule.setNamespace(profile.getNamespace());
    testWorkflowRule.setWorkflowServiceStubsOptions(profile.toWorkflowServiceStubsOptions());
    testWorkflowRule.setWorkflowClientOptions(profile.toWorkflowClientOptions());
    return testWorkflowRule;
  }

  static TestEnvironmentOptions.Builder configureFromEnvConfig(
      TestEnvironmentOptions.Builder testEnvironmentOptions, ClientConfigProfile profile) {
    validateEnvConfigProfile(profile);
    testEnvironmentOptions.setUseExternalService(true);
    testEnvironmentOptions.setTarget(profile.getAddress());
    testEnvironmentOptions.setWorkflowServiceStubsOptions(profile.toWorkflowServiceStubsOptions());
    testEnvironmentOptions.setWorkflowClientOptions(profile.toWorkflowClientOptions());
    return testEnvironmentOptions;
  }

  private static ClientConfigProfile loadEnvConfigProfile() {
    ClientConfigProfile profile;
    try {
      profile = ClientConfigProfile.load();
    } catch (IOException e) {
      throw new IllegalStateException(
          "Unable to load client configuration for the Temporal test harness.", e);
    }
    validateEnvConfigProfile(profile);
    return profile;
  }

  private static void validateEnvConfigProfile(ClientConfigProfile profile) {
    if (profile.getAddress() == null || profile.getAddress().isEmpty()) {
      throw new IllegalStateException("Envconfig test harness requires a Temporal server address.");
    }
    if (profile.getNamespace() == null || profile.getNamespace().isEmpty()) {
      throw new IllegalStateException("Envconfig test harness requires a Temporal namespace.");
    }
  }
}
