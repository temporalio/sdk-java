package io.temporal.internal.worker;

import static org.junit.Assert.*;

import io.temporal.api.worker.v1.EnvironmentInfo;
import io.temporal.api.worker.v1.EnvironmentInfo.Architecture;
import io.temporal.api.worker.v1.EnvironmentInfo.HostingEnvironment;
import io.temporal.api.worker.v1.EnvironmentInfo.HostingEnvironment.HostingEnvironmentType;
import io.temporal.api.worker.v1.EnvironmentInfo.Runtime.RuntimeType;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.Test;

public class WorkerEnvironmentInfoTest {

  @Test
  public void detectReportsJvmRuntimeAndPlatform() {
    EnvironmentInfo info = WorkerEnvironmentInfo.detect();

    assertEquals(1, info.getRuntimesCount());
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
  public void javaMajorVersion() {
    assertEquals(8, WorkerEnvironmentInfo.javaMajorVersion("1.8"));
    assertEquals(11, WorkerEnvironmentInfo.javaMajorVersion("11"));
    assertEquals(21, WorkerEnvironmentInfo.javaMajorVersion("21.0.1"));
    assertEquals(0, WorkerEnvironmentInfo.javaMajorVersion(null));
    assertEquals(0, WorkerEnvironmentInfo.javaMajorVersion("unknown"));
  }
}
