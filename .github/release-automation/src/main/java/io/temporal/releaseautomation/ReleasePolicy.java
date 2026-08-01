package io.temporal.releaseautomation;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ReleasePolicy {
  public static final String MAVEN_POLICY_CURRENT = "current";
  public static final String MAVEN_POLICY_CLASSIC = "classic";
  public static final String MAVEN_POLICY_CLASSIC_ALPHA = "classic-alpha";
  public static final String MAVEN_POLICY_CLASSIC_ALPHA_LITE = "classic-alpha-lite";
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
  private static final List<String> CLASSIC_MAVEN_ARTIFACTS =
      Collections.unmodifiableList(
          Arrays.asList(
              "temporal-bom",
              "temporal-kotlin",
              "temporal-opentracing",
              "temporal-remote-data-encoder",
              "temporal-sdk",
              "temporal-serviceclient",
              "temporal-shaded",
              "temporal-spring-boot-autoconfigure",
              "temporal-spring-boot-starter",
              "temporal-test-server",
              "temporal-testing"));
  private static final List<String> CLASSIC_ALPHA_MAVEN_ARTIFACTS =
      Collections.unmodifiableList(
          Arrays.asList(
              "temporal-bom",
              "temporal-kotlin",
              "temporal-opentracing",
              "temporal-remote-data-encoder",
              "temporal-sdk",
              "temporal-serviceclient",
              "temporal-shaded",
              "temporal-spring-boot-autoconfigure-alpha",
              "temporal-spring-boot-starter-alpha",
              "temporal-test-server",
              "temporal-testing"));
  private static final List<String> CLASSIC_ALPHA_LITE_MAVEN_ARTIFACTS =
      Collections.unmodifiableList(
          Arrays.asList(
              "temporal-kotlin",
              "temporal-opentracing",
              "temporal-remote-data-encoder",
              "temporal-sdk",
              "temporal-serviceclient",
              "temporal-spring-boot-autoconfigure-alpha",
              "temporal-spring-boot-starter-alpha",
              "temporal-test-server",
              "temporal-testing"));

  private ReleasePolicy() {}

  public static List<String> mavenArtifacts(String policy) {
    if (MAVEN_POLICY_CURRENT.equals(policy)) {
      return MAVEN_ARTIFACTS;
    }
    if (MAVEN_POLICY_CLASSIC.equals(policy)) {
      return CLASSIC_MAVEN_ARTIFACTS;
    }
    if (MAVEN_POLICY_CLASSIC_ALPHA.equals(policy)) {
      return CLASSIC_ALPHA_MAVEN_ARTIFACTS;
    }
    if (MAVEN_POLICY_CLASSIC_ALPHA_LITE.equals(policy)) {
      return CLASSIC_ALPHA_LITE_MAVEN_ARTIFACTS;
    }
    throw new IllegalArgumentException("Unsupported sdk-java Maven release policy.");
  }

  public static String mavenPolicyForProjects(List<String> projects) {
    Set<String> actual = new HashSet<>(projects);
    if (actual.size() != projects.size()) {
      throw new IllegalArgumentException("sdk-java settings contain duplicate projects.");
    }
    if (actual.equals(new HashSet<>(MAVEN_ARTIFACTS))) {
      return MAVEN_POLICY_CURRENT;
    }
    if (actual.equals(new HashSet<>(CLASSIC_MAVEN_ARTIFACTS))) {
      return MAVEN_POLICY_CLASSIC;
    }
    if (actual.equals(new HashSet<>(CLASSIC_ALPHA_MAVEN_ARTIFACTS))) {
      return MAVEN_POLICY_CLASSIC_ALPHA;
    }
    if (actual.equals(new HashSet<>(CLASSIC_ALPHA_LITE_MAVEN_ARTIFACTS))) {
      return MAVEN_POLICY_CLASSIC_ALPHA_LITE;
    }
    throw new IllegalArgumentException(
        "The immutable source does not match a reviewed sdk-java Maven policy.");
  }

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
