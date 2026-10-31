package io.temporal.internal.worker;

import static org.junit.Assert.*;

import io.temporal.api.worker.v1.EnvironmentInfo;
import io.temporal.api.worker.v1.EnvironmentInfo.Architecture;
import io.temporal.api.worker.v1.EnvironmentInfo.HostingEnvironment;
import io.temporal.api.worker.v1.EnvironmentInfo.HostingEnvironment.HostingEnvironmentType;
import io.temporal.api.worker.v1.EnvironmentInfo.Runtime.RuntimeType;
import java.io.File;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class WorkerEnvironmentInfoTest {

  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void detectReportsJvmRuntimeAndPlatform() {
    EnvironmentInfo info = WorkerEnvironmentInfo.detect();

    assertTrue(info.getRuntimesCount() >= 1);
    assertEquals(RuntimeType.RUNTIME_TYPE_JVM, info.getRuntimes(0).getType());
    assertEquals(System.getProperty("java.version"), info.getRuntimes(0).getVersion());

    Architecture expectedArchitecture = WorkerEnvironmentInfo.detectArchitecture();
    assertTrue(info.hasPlatform());
    switch (info.getPlatform().getVariantCase()) {
      case LINUX:
        assertEquals(expectedArchitecture, info.getPlatform().getLinux().getArchitecture());
        assertFalse(info.getPlatform().getLinux().getVersion().isEmpty());
        break;
      case MACOS:
        assertEquals(expectedArchitecture, info.getPlatform().getMacos().getArchitecture());
        break;
      case WINDOWS:
        assertEquals(expectedArchitecture, info.getPlatform().getWindows().getArchitecture());
        break;
      default:
        fail("unexpected platform variant " + info.getPlatform().getVariantCase());
    }
  }

  @Test
  public void detectGraalNativeImageRuntime() {
    Map<String, String> properties = new HashMap<>();
    properties.put("java.version", "21.0.2");
    properties.put("org.graalvm.nativeimage.imagecode", "runtime");
    properties.put("org.graalvm.version", "24.0.1");
    EnvironmentInfo info = WorkerEnvironmentInfo.detect(properties::get);
    assertEquals(2, info.getRuntimesCount());
    assertEquals(RuntimeType.RUNTIME_TYPE_JVM, info.getRuntimes(0).getType());
    assertEquals("21.0.2", info.getRuntimes(0).getVersion());
    assertEquals(RuntimeType.RUNTIME_TYPE_GRAAL_AOT, info.getRuntimes(1).getType());
    assertEquals("24.0.1", info.getRuntimes(1).getVersion());

    properties.remove("org.graalvm.version");
    assertEquals("", WorkerEnvironmentInfo.detect(properties::get).getRuntimes(1).getVersion());
    properties.put("org.graalvm.nativeimage.imagecode", "buildtime");
    assertEquals(1, WorkerEnvironmentInfo.detect(properties::get).getRuntimesCount());
    properties.remove("org.graalvm.nativeimage.imagecode");
    assertEquals(1, WorkerEnvironmentInfo.detect(properties::get).getRuntimesCount());
  }

  @Test
  public void runtimeProbeFailuresAreIsolated() {
    for (boolean linkageError : new boolean[] {false, true}) {
      EnvironmentInfo baseline = WorkerEnvironmentInfo.detect();
      EnvironmentInfo info =
          WorkerEnvironmentInfo.detect(
              name -> {
                if ("org.graalvm.nativeimage.imagecode".equals(name)) {
                  throwProbeFailure(linkageError);
                }
                return System.getProperty(name);
              });
      assertEquals(RuntimeType.RUNTIME_TYPE_JVM, info.getRuntimes(0).getType());
      assertEquals(System.getProperty("java.version"), info.getRuntimes(0).getVersion());
      assertEquals(baseline.getPlatform(), info.getPlatform());
      assertEquals(baseline.getHostingEnvironmentsList(), info.getHostingEnvironmentsList());

      info =
          WorkerEnvironmentInfo.detect(
              name -> {
                if ("org.graalvm.nativeimage.imagecode".equals(name)) {
                  return "runtime";
                }
                throwProbeFailure(linkageError);
                return null;
              });
      assertEquals(2, info.getRuntimesCount());
      assertEquals("", info.getRuntimes(0).getVersion());
      assertEquals(RuntimeType.RUNTIME_TYPE_GRAAL_AOT, info.getRuntimes(1).getType());
      assertEquals("", info.getRuntimes(1).getVersion());
      assertEquals(baseline.getPlatform(), info.getPlatform());
      assertEquals(baseline.getHostingEnvironmentsList(), info.getHostingEnvironmentsList());
    }
  }

  @Test
  public void kotlinAugmentationPreservesOptOutAndJavaImplementations() {
    EnvironmentInfo info = WorkerEnvironmentInfo.detect();
    assertFalse(WorkerEnvironmentInfo.isKotlinImplementation(null));
    assertFalse(WorkerEnvironmentInfo.isKotlinImplementation(getClass()));
    assertNull(WorkerEnvironmentInfo.withKotlinRuntime(null, getClass()));
    assertSame(info, WorkerEnvironmentInfo.withKotlinRuntime(info, null));
    assertSame(info, WorkerEnvironmentInfo.withKotlinRuntime(info, getClass()));
  }

  @Test
  public void kotlinMetadataDetectedAcrossClassLoaderBoundary() throws Exception {
    File classes = compileKotlinFixtures(true);
    try (URLClassLoader loader = fixtureLoader(classes, null, false)) {
      Class<?> implementation = loader.loadClass("fixtures.Implementation");
      assertTrue(WorkerEnvironmentInfo.isKotlinImplementation(implementation));
      EnvironmentInfo info = WorkerEnvironmentInfo.detect();
      EnvironmentInfo augmented = WorkerEnvironmentInfo.withKotlinRuntime(info, implementation);
      assertEquals(info.getRuntimesCount() + 1, augmented.getRuntimesCount());
      assertEquals(info.getPlatform(), augmented.getPlatform());
      assertEquals(info.getHostingEnvironmentsList(), augmented.getHostingEnvironmentsList());
      assertEquals(
          RuntimeType.RUNTIME_TYPE_KOTLIN,
          augmented.getRuntimes(info.getRuntimesCount()).getType());
      assertEquals("2.1.0", augmented.getRuntimes(info.getRuntimesCount()).getVersion());
      assertSame(augmented, WorkerEnvironmentInfo.withKotlinRuntime(augmented, implementation));
      assertEquals(
          info.getRuntimesList(), augmented.getRuntimesList().subList(0, info.getRuntimesCount()));
      assertNull(WorkerEnvironmentInfo.withKotlinRuntime(null, implementation));
    }
  }

  @Test
  public void kotlinVersionIsOptional() throws Exception {
    File classes = compileKotlinFixtures(false);
    try (URLClassLoader loader = fixtureLoader(classes, null, false)) {
      EnvironmentInfo info =
          WorkerEnvironmentInfo.withKotlinRuntime(
              EnvironmentInfo.getDefaultInstance(), loader.loadClass("fixtures.Implementation"));
      assertEquals(RuntimeType.RUNTIME_TYPE_KOTLIN, info.getRuntimes(0).getType());
      assertEquals("", info.getRuntimes(0).getVersion());
    }
  }

  @Test
  public void kotlinProbeFailuresAreBestEffort() throws Exception {
    File classes = compileKotlinFixtures(true);
    for (boolean linkageError : new boolean[] {false, true}) {
      try (URLClassLoader loader = fixtureLoader(classes, "kotlin.Metadata", linkageError)) {
        Class<?> implementation = loader.loadClass("fixtures.Implementation");
        EnvironmentInfo info = EnvironmentInfo.getDefaultInstance();
        assertFalse(WorkerEnvironmentInfo.isKotlinImplementation(implementation));
        assertSame(info, WorkerEnvironmentInfo.withKotlinRuntime(info, implementation));
      }
      try (URLClassLoader loader = fixtureLoader(classes, "kotlin.KotlinVersion", linkageError)) {
        EnvironmentInfo info =
            WorkerEnvironmentInfo.withKotlinRuntime(
                EnvironmentInfo.getDefaultInstance(), loader.loadClass("fixtures.Implementation"));
        assertEquals(RuntimeType.RUNTIME_TYPE_KOTLIN, info.getRuntimes(0).getType());
        assertEquals("", info.getRuntimes(0).getVersion());
      }
    }
  }

  private static void throwProbeFailure(boolean linkageError) {
    if (linkageError) {
      throw new LinkageError("Unavailable telemetry probe.");
    }
    throw new SecurityException("Unavailable telemetry probe.");
  }

  private URLClassLoader fixtureLoader(File classes, String failingClass, boolean linkageError)
      throws Exception {
    // No parent: the fixtures neither need nor accidentally use a real Kotlin dependency.
    return new URLClassLoader(new URL[] {classes.toURI().toURL()}, null) {
      @Override
      protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.equals(failingClass)) {
          throwProbeFailure(linkageError);
        }
        return super.loadClass(name, resolve);
      }
    };
  }

  private File compileKotlinFixtures(boolean includeVersion) throws Exception {
    File classes = temporaryFolder.newFolder();
    List<JavaFileObject> sources =
        new java.util.ArrayList<>(
            Arrays.asList(
                source(
                    "kotlin.Metadata",
                    "package kotlin; @java.lang.annotation.Retention("
                        + "java.lang.annotation.RetentionPolicy.RUNTIME) public @interface Metadata {}"),
                source(
                    "fixtures.Implementation",
                    "package fixtures; @kotlin.Metadata public class Implementation {}")));
    if (includeVersion) {
      sources.add(
          source(
              "kotlin.KotlinVersion",
              "package kotlin; public class KotlinVersion {"
                  + " public static final KotlinVersion CURRENT = new KotlinVersion();"
                  + " public String toString() { return \"2.1.0\"; } }"));
    }
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull("The fixture compiler requires a JDK.", compiler);
    assertTrue(
        compiler
            .getTask(
                null,
                null,
                null,
                Arrays.asList("-source", "8", "-target", "8", "-d", classes.getAbsolutePath()),
                null,
                sources)
            .call());
    return classes;
  }

  private static JavaFileObject source(String name, String contents) {
    return new SimpleJavaFileObject(
        URI.create("string:///" + name.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
      @Override
      public CharSequence getCharContent(boolean ignoreEncodingErrors) {
        return contents;
      }
    };
  }

  @Test
  public void detectHostingEnvironments() {
    Map<String, String> env = new HashMap<>();
    env.put("KUBERNETES_SERVICE_HOST", "10.0.0.1");
    env.put("ECS_CONTAINER_METADATA_URI", "http://169.254.170.2/v3");
    env.put("WEBSITE_SITE_NAME", "my-site");
    env.put("WEBSITE_PLATFORM_VERSION", " 1.2.3 ");
    env.put("FUNCTIONS_EXTENSION_VERSION", "~4");
    env.put("GAE_SERVICE", "   ");

    // Docker is detected from the host filesystem, so exclude it to keep the test host-independent.
    List<HostingEnvironment> environments =
        WorkerEnvironmentInfo.detectHostingEnvironments(env::get).stream()
            .filter(e -> e.getType() != HostingEnvironmentType.HOSTING_ENVIRONMENT_TYPE_DOCKER)
            .collect(Collectors.toList());

    assertEquals(
        Arrays.asList(
            HostingEnvironmentType.HOSTING_ENVIRONMENT_TYPE_K8S,
            HostingEnvironmentType.HOSTING_ENVIRONMENT_TYPE_AWS_ECS,
            HostingEnvironmentType.HOSTING_ENVIRONMENT_TYPE_AZURE_APP_SERVICE,
            HostingEnvironmentType.HOSTING_ENVIRONMENT_TYPE_AZURE_FUNCTIONS),
        environments.stream().map(HostingEnvironment::getType).collect(Collectors.toList()));
    assertEquals("1.2.3", environments.get(2).getVersion());
    assertEquals("~4", environments.get(3).getVersion());

    assertTrue(
        WorkerEnvironmentInfo.detectHostingEnvironments(name -> null).stream()
            .noneMatch(e -> e.getType() != HostingEnvironmentType.HOSTING_ENVIRONMENT_TYPE_DOCKER));
  }

  @Test
  public void cgroupsIndicateDocker() {
    assertFalse(WorkerEnvironmentInfo.cgroupsIndicateDocker(Collections.singletonList("0::/")));
    assertTrue(
        WorkerEnvironmentInfo.cgroupsIndicateDocker(
            Arrays.asList("12:pids:/docker/abc123", "0::/")));
    assertTrue(
        WorkerEnvironmentInfo.cgroupsIndicateDocker(
            Collections.singletonList("0::/system.slice/docker-abc123.scope")));
    assertFalse(
        WorkerEnvironmentInfo.cgroupsIndicateDocker(
            Collections.singletonList("0::/system.slice/docker-abc123.service")));
    assertFalse(
        WorkerEnvironmentInfo.cgroupsIndicateDocker(
            Collections.singletonList("0::/kubepods/besteffort/pod123/dockerish")));
  }

  @Test
  public void windowsVersion() {
    assertEquals("11", WorkerEnvironmentInfo.windowsVersion("Windows 11", "10.0"));
    assertEquals("10.0", WorkerEnvironmentInfo.windowsVersion("Windows 10", "10.0"));
    assertEquals("8.1", WorkerEnvironmentInfo.windowsVersion("Windows 8.1", "6.3"));
    assertEquals("10.0", WorkerEnvironmentInfo.windowsVersion("Windows Server 2022", "10.0"));
    assertEquals("5.1", WorkerEnvironmentInfo.windowsVersion("Windows XP", "5.1"));
    assertEquals("10.0", WorkerEnvironmentInfo.windowsVersion("Windows NT (unknown)", "10.0"));
    assertEquals("6.2", WorkerEnvironmentInfo.windowsVersion("Windows", "6.2"));
  }

  @Test
  public void javaMajorVersion() {
    assertEquals(8, WorkerEnvironmentInfo.javaMajorVersion("1.8"));
    assertEquals(11, WorkerEnvironmentInfo.javaMajorVersion("11"));
    assertEquals(21, WorkerEnvironmentInfo.javaMajorVersion("21.0.1"));
    assertEquals(0, WorkerEnvironmentInfo.javaMajorVersion(null));
    assertEquals(0, WorkerEnvironmentInfo.javaMajorVersion("unknown"));
  }
}
