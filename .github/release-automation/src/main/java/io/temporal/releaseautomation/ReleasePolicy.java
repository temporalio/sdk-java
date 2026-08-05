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
  public static final String NATIVE_JAVA_DISTRIBUTION = "graalvm-community";
  public static final String NATIVE_JAVA_VERSION = "23";
  public static final List<PlatformSpec> PLATFORMS =
      Collections.unmodifiableList(
          Arrays.asList(
              new PlatformSpec("linux-amd64-musl", "ubuntu-latest", "linux", "amd64", true),
              new PlatformSpec("linux-amd64", "ubuntu-latest", "linux", "amd64", false),
              new PlatformSpec("macos-amd64", "macos-15-intel", "macOS", "amd64", false),
              new PlatformSpec("macos-arm64", "macos-latest", "macOS", "arm64", false),
              new PlatformSpec("linux-arm64", "ubuntu-24.04-arm", "linux", "arm64", false),
              new PlatformSpec("windows-amd64", "windows-latest", "windows", "amd64", false)));
  public static final List<String> NATIVE_PLATFORMS = platformNames();
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
    PlatformSpec spec = platform(platform);
    return "temporal-test-server_" + version + "_" + spec.assetPlatform + spec.archiveExtension;
  }

  static PlatformSpec platform(String id) {
    for (PlatformSpec platform : PLATFORMS) {
      if (platform.id.equals(id)) {
        return platform;
      }
    }
    throw new IllegalArgumentException("Unknown sdk-java native release platform.");
  }

  private static List<String> platformNames() {
    String[] names = new String[PLATFORMS.size()];
    for (int i = 0; i < PLATFORMS.size(); i++) {
      names[i] = PLATFORMS.get(i).id;
    }
    return Collections.unmodifiableList(Arrays.asList(names));
  }

  public static final class PlatformSpec {
    public final String id;
    public final String runner;
    public final String osFamily;
    public final String arch;
    public final boolean musl;
    public final String artifactLabel;
    public final String assetPlatform;
    public final String archiveExtension;
    public final String binaryName;
    public final String distribution;
    public final String javaVersion;

    private PlatformSpec(String id, String runner, String osFamily, String arch, boolean musl) {
      this.id = id;
      this.runner = runner;
      this.osFamily = osFamily;
      this.arch = arch;
      this.musl = musl;
      this.artifactLabel = osFamily + "_" + arch + (musl ? "_musl" : "");
      this.assetPlatform =
          (id.startsWith("macos-") ? "macOS" + id.substring(5) : id).replace('-', '_');
      this.archiveExtension = "windows".equals(osFamily) ? ".zip" : ".tar.gz";
      this.binaryName =
          "windows".equals(osFamily) ? "temporal-test-server.exe" : "temporal-test-server";
      this.distribution = "linux".equals(osFamily) ? "" : NATIVE_JAVA_DISTRIBUTION;
      this.javaVersion = "linux".equals(osFamily) ? "" : NATIVE_JAVA_VERSION;
    }
  }
}
