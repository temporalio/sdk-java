package io.temporal.testing.internal;

import io.grpc.Metadata;
import io.temporal.client.ActivityClientOptions;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.envconfig.ClientConfigProfile;
import io.temporal.envconfig.LoadClientConfigProfileOptions;
import io.temporal.serviceclient.GrpcMetadataProvider;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowRule;
import io.temporal.testing.internal.devserver.SdkJavaTestServerProfile;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;

public class ExternalServiceTestConfigurator {
  static final String TEMPORAL_TEST_ENV_CONFIG_SERVER = "TEMPORAL_TEST_ENV_CONFIG_SERVER";
  private static final String USE_EXTERNAL_SERVICE = "USE_EXTERNAL_SERVICE";
  private static final String TEMPORAL_SERVICE_ADDRESS = "TEMPORAL_SERVICE_ADDRESS";
  private static final String USE_VIRTUAL_THREADS = "USE_VIRTUAL_THREADS";

  public static boolean isUseExternalService() {
    return isUseExternalService(System.getenv()) || SdkJavaTestServerProfile.isActive();
  }

  public static boolean isUseVirtualThreads() {
    return readBooleanFlag(System.getenv(), USE_VIRTUAL_THREADS);
  }

  public static String getTemporalServiceAddress() {
    Map<String, String> environment = System.getenv();
    if (readBooleanFlag(environment, TEMPORAL_TEST_ENV_CONFIG_SERVER)) {
      return getTemporalServiceAddress(environment);
    }
    String devServerTarget = SdkJavaTestServerProfile.getTarget();
    if (devServerTarget != null) {
      return devServerTarget;
    }
    return getTemporalServiceAddress(environment);
  }

  public static TestWorkflowRule.Builder configure(
      @Nonnull TestWorkflowRule.Builder testWorkflowRule) {
    return configure(testWorkflowRule, System.getenv(), SdkJavaTestServerProfile.getTarget());
  }

  static TestWorkflowRule.Builder configure(
      TestWorkflowRule.Builder testWorkflowRule, Map<String, String> environment) {
    return configure(testWorkflowRule, environment, null);
  }

  static TestWorkflowRule.Builder configure(
      TestWorkflowRule.Builder testWorkflowRule,
      Map<String, String> environment,
      String devServerTarget) {
    return configure(testWorkflowRule, environment, devServerTarget, true);
  }

  static TestWorkflowRule.Builder configureConnection(
      TestWorkflowRule.Builder testWorkflowRule,
      Map<String, String> environment,
      String devServerTarget) {
    return configure(testWorkflowRule, environment, devServerTarget, false);
  }

  private static TestWorkflowRule.Builder configure(
      TestWorkflowRule.Builder testWorkflowRule,
      Map<String, String> environment,
      String devServerTarget,
      boolean configureOptions) {
    ClientConfigProfile profile = loadEnvConfigProfile(environment);
    if (profile != null) {
      testWorkflowRule.setUseExternalService(true);
      testWorkflowRule.setTarget(profile.getAddress());
      testWorkflowRule.setNamespace(profile.getNamespace());
      if (configureOptions) {
        testWorkflowRule.setWorkflowServiceStubsOptions(profile.toWorkflowServiceStubsOptions());
        testWorkflowRule.setWorkflowClientOptions(profile.toWorkflowClientOptions());
      }
    } else if (devServerTarget != null) {
      testWorkflowRule.setUseExternalService(true);
      testWorkflowRule.setTarget(devServerTarget);
    } else if (readBooleanFlag(environment, USE_EXTERNAL_SERVICE)) {
      testWorkflowRule.setUseExternalService(true);
      String serviceAddress = environment.get(TEMPORAL_SERVICE_ADDRESS);
      if (serviceAddress != null) {
        testWorkflowRule.setTarget(serviceAddress);
      }
    }
    return testWorkflowRule;
  }

  public static TestEnvironmentOptions.Builder configure(
      @Nonnull TestEnvironmentOptions.Builder testEnvironmentOptions) {
    return configure(testEnvironmentOptions, System.getenv(), SdkJavaTestServerProfile.getTarget());
  }

  static TestEnvironmentOptions.Builder configure(
      TestEnvironmentOptions.Builder testEnvironmentOptions, Map<String, String> environment) {
    return configure(testEnvironmentOptions, environment, null);
  }

  static TestEnvironmentOptions.Builder configure(
      TestEnvironmentOptions.Builder testEnvironmentOptions,
      Map<String, String> environment,
      String devServerTarget) {
    ClientConfigProfile profile = loadEnvConfigProfile(environment);
    if (profile != null) {
      testEnvironmentOptions.setUseExternalService(true);
      testEnvironmentOptions.setTarget(profile.getAddress());
      testEnvironmentOptions.setWorkflowServiceStubsOptions(
          profile.toWorkflowServiceStubsOptions());
      testEnvironmentOptions.setWorkflowClientOptions(profile.toWorkflowClientOptions());
    } else if (devServerTarget != null) {
      testEnvironmentOptions.setUseExternalService(true);
      testEnvironmentOptions.setTarget(devServerTarget);
    } else if (readBooleanFlag(environment, USE_EXTERNAL_SERVICE)) {
      testEnvironmentOptions.setUseExternalService(true);
      String serviceAddress = environment.get(TEMPORAL_SERVICE_ADDRESS);
      if (serviceAddress != null) {
        testEnvironmentOptions.setTarget(serviceAddress);
      }
    }
    return testEnvironmentOptions;
  }

  public static TestEnvironmentOptions.Builder configuredTestEnvironmentOptions() {
    return configure(TestEnvironmentOptions.newBuilder());
  }

  static WorkflowServiceStubsOptions configure(
      WorkflowServiceStubsOptions workflowServiceStubsOptions, Map<String, String> environment) {
    ClientConfigProfile profile = loadEnvConfigProfile(environment);
    if (profile == null) {
      return workflowServiceStubsOptions;
    }

    WorkflowServiceStubsOptions profileOptions = profile.toWorkflowServiceStubsOptions();
    GrpcMetadataProvider metadataProvider =
        mergeMetadata(
            profileOptions.getHeaders(),
            profileOptions.getGrpcMetadataProviders(),
            workflowServiceStubsOptions.getHeaders(),
            workflowServiceStubsOptions.getGrpcMetadataProviders());
    return WorkflowServiceStubsOptions.newBuilder(workflowServiceStubsOptions)
        .setChannel(null)
        .setTarget(profileOptions.getTarget())
        .setEnableHttps(profileOptions.getEnableHttps())
        .setSslContext(profileOptions.getSslContext())
        .setChannelInitializer(profileOptions.getChannelInitializer())
        .setHeaders(new Metadata())
        .setGrpcMetadataProviders(Collections.singletonList(metadataProvider))
        .build();
  }

  private static GrpcMetadataProvider mergeMetadata(
      Metadata profileHeaders,
      Iterable<GrpcMetadataProvider> profileProviders,
      Metadata testHeaders,
      Iterable<GrpcMetadataProvider> testProviders) {
    List<GrpcMetadataProvider> profileProviderList = new ArrayList<>();
    profileProviders.forEach(profileProviderList::add);
    return () -> {
      Metadata metadata = new Metadata();
      if (testHeaders != null) {
        metadata.merge(testHeaders);
      }
      testProviders.forEach(provider -> metadata.merge(provider.getMetadata()));
      Metadata profileMetadata = new Metadata();
      if (profileHeaders != null) {
        profileMetadata.merge(profileHeaders);
      }
      profileProviderList.forEach(provider -> profileMetadata.merge(provider.getMetadata()));
      for (String keyName : profileMetadata.keys()) {
        if (keyName.endsWith("-bin")) {
          metadata.discardAll(Metadata.Key.of(keyName, Metadata.BINARY_BYTE_MARSHALLER));
        } else {
          metadata.discardAll(Metadata.Key.of(keyName, Metadata.ASCII_STRING_MARSHALLER));
        }
      }
      metadata.merge(profileMetadata);
      return metadata;
    };
  }

  static WorkflowClientOptions configure(
      WorkflowClientOptions workflowClientOptions, Map<String, String> environment) {
    ClientConfigProfile profile = loadEnvConfigProfile(environment);
    return profile == null
        ? workflowClientOptions
        : WorkflowClientOptions.newBuilder(workflowClientOptions)
            .setNamespace(profile.getNamespace())
            .build();
  }

  static ActivityClientOptions configure(
      ActivityClientOptions activityClientOptions, Map<String, String> environment) {
    ClientConfigProfile profile = loadEnvConfigProfile(environment);
    return profile == null
        ? activityClientOptions
        : ActivityClientOptions.newBuilder(activityClientOptions)
            .setNamespace(profile.getNamespace())
            .build();
  }

  static boolean isUseExternalService(Map<String, String> environment) {
    return readBooleanFlag(environment, TEMPORAL_TEST_ENV_CONFIG_SERVER)
        || readBooleanFlag(environment, USE_EXTERNAL_SERVICE);
  }

  static String getTemporalServiceAddress(Map<String, String> environment) {
    ClientConfigProfile profile = loadEnvConfigProfile(environment);
    if (profile != null) {
      return profile.getAddress();
    }
    return readBooleanFlag(environment, USE_EXTERNAL_SERVICE)
        ? (environment.get(TEMPORAL_SERVICE_ADDRESS) != null
            ? environment.get(TEMPORAL_SERVICE_ADDRESS)
            : "127.0.0.1:7233")
        : null;
  }

  private static ClientConfigProfile loadEnvConfigProfile(Map<String, String> environment) {
    if (!readBooleanFlag(environment, TEMPORAL_TEST_ENV_CONFIG_SERVER)) {
      return null;
    }

    ClientConfigProfile profile;
    try {
      profile =
          ClientConfigProfile.load(
              LoadClientConfigProfileOptions.newBuilder().setEnvOverrides(environment).build());
    } catch (IOException e) {
      throw new IllegalStateException(
          "Unable to load client configuration for the Temporal test harness.", e);
    }
    if (profile.getAddress() == null || profile.getAddress().isEmpty()) {
      throw new IllegalStateException("Envconfig test harness requires a Temporal server address.");
    }
    if (profile.getNamespace() == null || profile.getNamespace().isEmpty()) {
      throw new IllegalStateException("Envconfig test harness requires a Temporal namespace.");
    }
    return profile;
  }

  private static boolean readBooleanFlag(Map<String, String> environment, String variableName) {
    String value = environment.get(variableName);
    if (value == null) {
      return false;
    }
    value = value.trim();
    return !Boolean.FALSE.toString().equalsIgnoreCase(value) && !"0".equals(value);
  }
}
