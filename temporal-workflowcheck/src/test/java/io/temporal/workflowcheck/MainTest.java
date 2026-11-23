package io.temporal.workflowcheck;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.workflowcheck.testdata.Configured;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class MainTest {
  @Rule public final TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void loadsConfigFromEnvironment() throws Exception {
    assertCheckExitCode(1, configFile("environment rules.properties", true).toString());
  }

  @Test
  public void explicitConfigOverridesEnvironment() throws Exception {
    Path environment = configFile("environment.properties", true);
    Path explicit = configFile("explicit.properties", false);
    assertCheckExitCode(0, environment.toString(), "--config", explicit.toString());
  }

  @Test
  public void laterExplicitConfigStillWins() throws Exception {
    Path first = configFile("first.properties", true);
    Path second = configFile("second.properties", false);
    assertCheckExitCode(0, null, "--config", first.toString(), "--config", second.toString());
  }

  @Test
  public void emptyEnvironmentUsesDefaultConfig() throws Exception {
    assertCheckExitCode(0, "");
  }

  @Test
  public void worksWithoutDefaultConfig() throws Exception {
    assertCheckExitCode(
        1, configFile("environment.properties", true).toString(), "--no-default-config");
  }

  private Path configFile(String name, boolean invalid) throws Exception {
    Path file = temp.newFile(name).toPath();
    Files.write(
        file,
        ("temporal.workflowcheck.invalid.configuredInvalidJustName=" + invalid + "\n")
            .getBytes(StandardCharsets.UTF_8));
    return file;
  }

  private void assertCheckExitCode(int expected, String environment, String... args)
      throws Exception {
    Path classpath = temp.newFolder().toPath();
    for (Class<?> type : Arrays.asList(Configured.class, Configured.ConfiguredImpl.class)) {
      String name = type.getName().replace('.', '/') + ".class";
      Path target = classpath.resolve(name);
      Files.createDirectories(target.getParent());
      try (InputStream input = type.getResourceAsStream("/" + name)) {
        Files.copy(input, target);
      }
    }

    String executable = File.separatorChar == '\\' ? "java.exe" : "java";
    List<String> command =
        new ArrayList<>(
            Arrays.asList(
                Paths.get(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp",
                System.getProperty("java.class.path"),
                Main.class.getName(),
                "check",
                classpath.toString()));
    command.addAll(Arrays.asList(args));
    File output = temp.newFile();
    ProcessBuilder builder =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output);
    if (environment == null) {
      builder.environment().remove("TEMPORAL_WORKFLOWCHECK_CONFIG");
    } else {
      builder.environment().put("TEMPORAL_WORKFLOWCHECK_CONFIG", environment);
    }
    Process process = builder.start();
    try {
      assertTrue("workflowcheck did not exit", process.waitFor(30, TimeUnit.SECONDS));
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
      }
    }
    String result = new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8);
    assertTrue(result, result.contains("Found 1 class(es) with workflow methods"));
    assertEquals(result, expected, process.exitValue());
  }
}
