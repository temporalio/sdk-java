package io.temporal.releaseautomation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.testing.TestActivityEnvironment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ProcessSupportTest {
  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void shellScriptsUseExplicitBashAndPortableSeparators() {
    List<String> command = ProcessSupport.bash(Paths.get("trusted\\release-script.sh"));
    assertEquals(
        java.io.File.separatorChar == '\\' ? "C:\\Program Files\\Git\\bin\\bash.exe" : "bash",
        command.get(0));
    assertTrue(command.get(1).endsWith("trusted/release-script.sh"));
    assertTrue(!command.get(1).contains("\\"));
    assertEquals(
        "/d/trusted/release-script.sh", ProcessSupport.bashPath("D:\\trusted\\release-script.sh"));
  }

  @Test
  public void explicitBashCommandRunsInsideActivityEnvironment() throws Exception {
    Path script = temporaryFolder.newFile("release-script.sh").toPath();
    Files.write(
        script,
        "#!/usr/bin/env bash\nprintf 'trusted-worker-output\\n'\n"
            .getBytes(StandardCharsets.UTF_8));

    TestActivityEnvironment environment = TestActivityEnvironment.newInstance();
    try {
      environment.registerActivitiesImplementations(new ShellActivityImpl());
      ShellActivity activity = environment.newActivityStub(ShellActivity.class);

      assertEquals(
          Collections.singletonList("trusted-worker-output"),
          activity.run(script.toAbsolutePath().toString()));
    } finally {
      environment.close();
    }
  }

  @Test
  public void terminationStopsTheWholeProcessTree() throws Exception {
    Process process = new ProcessBuilder("bash", "-c", "sleep 30 & wait").start();
    List<ProcessHandle> descendants = Collections.emptyList();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (descendants.isEmpty() && System.nanoTime() < deadline) {
      descendants = process.descendants().collect(Collectors.toList());
      Thread.sleep(25);
    }
    assertFalse("Expected the shell command to start a child process.", descendants.isEmpty());

    ProcessSupport.terminateProcessTree(process);

    assertFalse(process.isAlive());
    assertTrue(descendants.stream().noneMatch(ProcessHandle::isAlive));
  }

  @ActivityInterface
  public interface ShellActivity {
    @ActivityMethod
    List<String> run(String script);
  }

  public static final class ShellActivityImpl implements ShellActivity {
    @Override
    public List<String> run(String script) {
      return ProcessSupport.run(
          Paths.get("").toAbsolutePath(),
          ProcessSupport.bash(Paths.get(script)),
          Collections.emptyMap());
    }
  }
}
