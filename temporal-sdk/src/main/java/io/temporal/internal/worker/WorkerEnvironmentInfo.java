package io.temporal.internal.worker;

import io.temporal.api.worker.v1.EnvironmentInfo;
import io.temporal.api.worker.v1.EnvironmentInfo.Architecture;
import io.temporal.api.worker.v1.EnvironmentInfo.HostingEnvironment;
import io.temporal.api.worker.v1.EnvironmentInfo.HostingEnvironment.HostingEnvironmentType;
import io.temporal.api.worker.v1.EnvironmentInfo.LinuxPlatform;
import io.temporal.api.worker.v1.EnvironmentInfo.MacOSPlatform;
import io.temporal.api.worker.v1.EnvironmentInfo.Platform;
import io.temporal.api.worker.v1.EnvironmentInfo.Runtime;
import io.temporal.api.worker.v1.EnvironmentInfo.Runtime.RuntimeType;
import io.temporal.api.worker.v1.EnvironmentInfo.WindowsPlatform;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * Detects the runtime, hosting environment, and platform information reported in the first accepted
 * worker heartbeat.
 */
public final class WorkerEnvironmentInfo {

  private WorkerEnvironmentInfo() {}

  public static EnvironmentInfo detect() {
    EnvironmentInfo.Builder builder =
        EnvironmentInfo.newBuilder()
            .addRuntimes(
                Runtime.newBuilder()
                    .setType(RuntimeType.RUNTIME_TYPE_JVM)
                    .setVersion(nullToEmpty(System.getProperty("java.version"))))
            .addAllHostingEnvironments(detectHostingEnvironments(System::getenv));
    Platform platform = detectPlatform();
    if (platform != null) {
      builder.setPlatform(platform);
    }
    return builder.build();
  }

  /**
   * Several environments may be detected at once, e.g. Docker inside Kubernetes or Azure Functions
   * inside Azure App Service.
   */
  static List<HostingEnvironment> detectHostingEnvironments(Function<String, String> env) {
    List<HostingEnvironment> environments = new ArrayList<>();
    if (isDocker()) {
      environments.add(
          hostingEnvironment(HostingEnvironmentType.HOSTING_ENVIRONMENT_TYPE_DOCKER, ""));
    }
    if (hasAnyEnv(env, "KUBERNETES_SERVICE_HOST")) {
      environments.add(hostingEnvironment(HostingEnvironmentType.HOSTING_ENVIRONMENT_TYPE_K8S, ""));
    }
    if (hasAnyEnv(env, "AWS_LAMBDA_FUNCTION_NAME")) {
      environments.add(
          hostingEnvironment(HostingEnvironmentType.HOSTING_ENVIRONMENT_TYPE_AWS_LAMBDA, ""));
    }
    if (hasAnyEnv(env, "ECS_CONTAINER_METADATA_URI_V4", "ECS_CONTAINER_METADATA_URI")) {
      environments.add(
          hostingEnvironment(HostingEnvironmentType.HOSTING_ENVIRONMENT_TYPE_AWS_ECS, ""));
    }
    if (hasAnyEnv(env, "K_SERVICE", "CLOUD_RUN_JOB", "CLOUD_RUN_WORKER_POOL")) {
      environments.add(
          hostingEnvironment(HostingEnvironmentType.HOSTING_ENVIRONMENT_TYPE_GOOGLE_CLOUD_RUN, ""));
    }
    if (hasAnyEnv(env, "GAE_SERVICE")) {
      environments.add(
          hostingEnvironment(
              HostingEnvironmentType.HOSTING_ENVIRONMENT_TYPE_GOOGLE_APP_ENGINE, ""));
    }
    if (hasAnyEnv(env, "WEBSITE_SITE_NAME")) {
      environments.add(
          hostingEnvironment(
              HostingEnvironmentType.HOSTING_ENVIRONMENT_TYPE_AZURE_APP_SERVICE,
              envValue(env, "WEBSITE_PLATFORM_VERSION")));
    }
    String functionsVersion = envValue(env, "FUNCTIONS_EXTENSION_VERSION");
    if (!functionsVersion.isEmpty()) {
      environments.add(
          hostingEnvironment(
              HostingEnvironmentType.HOSTING_ENVIRONMENT_TYPE_AZURE_FUNCTIONS, functionsVersion));
    }
    if (hasAnyEnv(env, "CONTAINER_APP_NAME", "CONTAINER_APP_JOB_NAME")) {
      environments.add(
          hostingEnvironment(
              HostingEnvironmentType.HOSTING_ENVIRONMENT_TYPE_AZURE_CONTAINER_APPS, ""));
    }
    return environments;
  }

  private static HostingEnvironment hostingEnvironment(
      HostingEnvironmentType type, String version) {
    return HostingEnvironment.newBuilder().setType(type).setVersion(version).build();
  }

  private static String envValue(Function<String, String> env, String name) {
    return nullToEmpty(env.apply(name)).trim();
  }

  private static boolean hasAnyEnv(Function<String, String> env, String... names) {
    for (String name : names) {
      if (!envValue(env, name).isEmpty()) {
        return true;
      }
    }
    return false;
  }

  private static boolean isDocker() {
    if (isWindows(System.getProperty("os.name"))) {
      return false;
    }
    if (Files.exists(Paths.get("/.dockerenv"))) {
      return true;
    }
    Path cgroup = Paths.get("/proc/self/cgroup");
    if (!Files.isReadable(cgroup)) {
      return false;
    }
    try {
      return cgroupsIndicateDocker(Files.readAllLines(cgroup, StandardCharsets.UTF_8));
    } catch (IOException | RuntimeException e) {
      return false;
    }
  }

  /**
   * Reports whether any cgroup path has a {@code docker} or {@code docker-<id>.scope} component.
   */
  static boolean cgroupsIndicateDocker(List<String> cgroupLines) {
    for (String line : cgroupLines) {
      int idx = line.lastIndexOf(':');
      String path = idx >= 0 ? line.substring(idx + 1) : line;
      for (String component : path.split("/")) {
        if (component.equals("docker")
            || (component.startsWith("docker-") && component.endsWith(".scope"))) {
          return true;
        }
      }
    }
    return false;
  }

  @Nullable
  private static Platform detectPlatform() {
    String name = nullToEmpty(System.getProperty("os.name")).toLowerCase(Locale.ROOT);
    String osVersion = nullToEmpty(System.getProperty("os.version"));
    Architecture architecture = detectArchitecture();
    if (name.contains("linux")) {
      return Platform.newBuilder()
          .setLinux(
              LinuxPlatform.newBuilder()
                  .setVersion(linuxVersion(osVersion))
                  .setArchitecture(architecture))
          .build();
    }
    if (name.contains("mac") || name.contains("darwin")) {
      return Platform.newBuilder()
          .setMacos(MacOSPlatform.newBuilder().setVersion(osVersion).setArchitecture(architecture))
          .build();
    }
    if (isWindows(name)) {
      return Platform.newBuilder()
          .setWindows(
              WindowsPlatform.newBuilder()
                  .setVersion(osVersion)
                  .setArchitecture(architecture)
                  .setCrt(
                      windowsCrt(
                          javaMajorVersion(System.getProperty("java.specification.version")))))
          .build();
    }
    return null;
  }

  static Architecture detectArchitecture() {
    switch (nullToEmpty(System.getProperty("os.arch")).toLowerCase(Locale.ROOT)) {
      case "amd64":
      case "x86_64":
        return Architecture.ARCHITECTURE_AMD64;
      case "aarch64":
      case "arm64":
        return Architecture.ARCHITECTURE_ARM64;
      default:
        return Architecture.ARCHITECTURE_UNSPECIFIED;
    }
  }

  /**
   * Windows JDKs before 11 were built with Visual Studio toolchains that ship their own MSVC
   * runtime; JDK 11 onward links against the Universal CRT.
   */
  private static WindowsPlatform.Crt windowsCrt(int javaMajor) {
    if (javaMajor <= 0) {
      return WindowsPlatform.Crt.CRT_UNSPECIFIED;
    }
    return javaMajor >= 11 ? WindowsPlatform.Crt.CRT_UCRT : WindowsPlatform.Crt.CRT_MSVCRT;
  }

  static int javaMajorVersion(@Nullable String specificationVersion) {
    String version = nullToEmpty(specificationVersion);
    if (version.startsWith("1.")) {
      version = version.substring(2);
    }
    int end = version.indexOf('.');
    if (end >= 0) {
      version = version.substring(0, end);
    }
    try {
      return Integer.parseInt(version);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static boolean isWindows(@Nullable String osName) {
    return nullToEmpty(osName).toLowerCase(Locale.ROOT).contains("windows");
  }

  /**
   * The JVM only exposes the kernel release as {@code os.version}; prefer the distribution version
   * from os-release to match what Core reports.
   */
  private static String linuxVersion(String kernelVersion) {
    for (String path : new String[] {"/etc/os-release", "/usr/lib/os-release"}) {
      String version = osReleaseValue(Paths.get(path), "VERSION_ID");
      if (!version.isEmpty()) {
        return version;
      }
    }
    return kernelVersion;
  }

  private static String osReleaseValue(Path path, String key) {
    if (!Files.isReadable(path)) {
      return "";
    }
    try {
      for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
        int idx = line.indexOf('=');
        if (idx > 0 && line.substring(0, idx).equals(key)) {
          String value = line.substring(idx + 1).trim();
          if (value.length() >= 2
              && (value.startsWith("\"") && value.endsWith("\"")
                  || value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1);
          }
          return value;
        }
      }
    } catch (IOException | RuntimeException e) {
      // Fall through to the caller's fallback.
    }
    return "";
  }

  private static String nullToEmpty(@Nullable String value) {
    return value == null ? "" : value;
  }
}
