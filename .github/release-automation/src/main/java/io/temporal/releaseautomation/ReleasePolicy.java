package io.temporal.releaseautomation;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class ReleasePolicy {
  public static final String REPOSITORY = "temporalio/sdk-java";
  public static final String MAVEN_GROUP = "io.temporal";
  public static final String MAVEN_CENTRAL_BASE = "https://repo1.maven.org/maven2";
  public static final List<String> NATIVE_PLATFORMS =
      Collections.unmodifiableList(
          Arrays.asList(
              "linux-amd64-musl",
              "linux-amd64",
              "macos-amd64",
              "macos-arm64",
              "linux-arm64",
              "windows-amd64"));
  public static final List<String> MAVEN_ARTIFACTS =
      Collections.unmodifiableList(
          Arrays.asList(
              "temporal-aws-lambda",
              "temporal-bom",
              "temporal-envconfig",
              "temporal-kotlin",
              "temporal-opentelemetry",
              "temporal-opentracing",
              "temporal-remote-data-encoder",
              "temporal-sdk",
              "temporal-serviceclient",
              "temporal-shaded",
              "temporal-spring-ai",
              "temporal-spring-boot-autoconfigure",
              "temporal-spring-boot-starter",
              "temporal-test-server",
              "temporal-testing",
              "temporal-workflowcheck",
              "temporal-workflowstreams"));

  private ReleasePolicy() {}

  static String nativeArtifactName(String version, String platform) {
    if (!NATIVE_PLATFORMS.contains(platform)) {
      throw new IllegalArgumentException("Unknown sdk-java native release platform.");
    }
    String suffix = "windows-amd64".equals(platform) ? ".zip" : ".tar.gz";
    String assetPlatform =
        platform.startsWith("macos-") ? "macOS" + platform.substring(5) : platform;
    return "temporal-test-server_" + version + "_" + assetPlatform.replace('-', '_') + suffix;
  }
}
