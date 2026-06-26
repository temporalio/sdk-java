package io.temporal.workflowcheck.gradle;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecResult;

/** Runs the temporal-workflowcheck command line analyzer in an isolated JVM. */
@CacheableTask
public abstract class WorkflowCheckTask extends DefaultTask {

  public static final String TASK_NAME = "workflowCheck";

  /**
   * Stable stdout summary the analyzer prints once an analysis completes. Used to distinguish a
   * completed scan (where exit code 1 means violations) from a pre-scan failure.
   */
  static final String COMPLETED_ANALYSIS_SUMMARY = "class(es) with workflow methods";

  /** The consumer's runtime classpath to analyze. */
  @InputFiles
  @Classpath
  public abstract ConfigurableFileCollection getClasspath();

  /** The temporal-workflowcheck tool classpath. */
  @InputFiles
  @Classpath
  public abstract ConfigurableFileCollection getToolClasspath();

  /** Additional {@code .properties} config files. */
  @InputFiles
  @PathSensitive(PathSensitivity.RELATIVE)
  @Optional
  public abstract ConfigurableFileCollection getConfigFiles();

  @Input
  public abstract Property<Boolean> getFailOnViolation();

  @Input
  public abstract Property<Boolean> getShowValid();

  @Input
  public abstract Property<Boolean> getNoDefaultConfig();

  /**
   * Ordered, project-relative config-file paths used to preserve overlay precedence in cache keys.
   */
  @Input
  public abstract ListProperty<String> getConfigFileOrder();

  /**
   * Whether to log each classpath entry at lifecycle level. When {@code false}, entries are logged
   * at info level so {@code --info} remains opt-in.
   */
  @Input
  public abstract Property<Boolean> getShowClasspath();

  @Input
  @Optional
  public abstract Property<String> getMaxHeapSize();

  @OutputFile
  public abstract RegularFileProperty getReportFile();

  @Option(option = "show-valid", description = "Show valid workflow methods alongside invalid ones")
  public void setShowValidOption(boolean value) {
    getShowValid().set(value);
  }

  @Option(
      option = "no-default-config",
      description = "Skip the default bundled workflowcheck config")
  public void setNoDefaultConfigOption(boolean value) {
    getNoDefaultConfig().set(value);
  }

  private final ExecOperations execOperations;

  @Inject
  public WorkflowCheckTask(ExecOperations execOperations) {
    this.execOperations = execOperations;
  }

  @TaskAction
  public void check() {
    if (getToolClasspath().isEmpty()) {
      getLogger().warn("WorkflowCheck: Tool classpath is empty. Skipping.");
      return;
    }

    List<File> classpathFiles = new ArrayList<>(getClasspath().getFiles());
    String maxHeap = getMaxHeapSize().getOrNull();
    getLogger()
        .lifecycle(
            "WorkflowCheck: scanning {} classpath entries (maxHeapSize={})",
            classpathFiles.size(),
            maxHeap != null ? maxHeap : "default");
    for (File file : classpathFiles) {
      if (getShowClasspath().get()) {
        getLogger().lifecycle("  {}", file.getAbsolutePath());
      } else {
        getLogger().info("  {}", file.getAbsolutePath());
      }
    }

    try {
      Path temporaryDirectory = getTemporaryDir().toPath();
      Path classpathFile = temporaryDirectory.resolve("classpath.txt");
      String classpathString = joinClasspath(classpathFiles);
      Files.write(classpathFile, classpathString.getBytes(StandardCharsets.UTF_8));

      List<String> args = new ArrayList<>();
      args.add("check");

      if (getNoDefaultConfig().get()) {
        args.add("--no-default-config");
      }

      for (File configFile : getConfigFiles().getFiles()) {
        args.add("--config");
        args.add(configFile.getAbsolutePath());
      }

      if (getShowValid().get()) {
        args.add("--show-valid");
      }

      args.add("@" + classpathFile.toAbsolutePath());

      ByteArrayOutputStream stdout = new ByteArrayOutputStream();
      ExecResult result =
          execOperations.javaexec(
              spec -> {
                spec.getMainClass().set("io.temporal.workflowcheck.Main");
                spec.classpath(getToolClasspath());
                spec.args(args);
                if (maxHeap != null) {
                  spec.setMaxHeapSize(maxHeap);
                }
                spec.setStandardOutput(stdout);
                spec.setIgnoreExitValue(true);
              });

      String output = stdout.toString(StandardCharsets.UTF_8.name());
      File reportFile = getReportFile().getAsFile().get();
      reportFile.getParentFile().mkdirs();
      Files.write(reportFile.toPath(), output.getBytes(StandardCharsets.UTF_8));

      if (!output.trim().isEmpty()) {
        getLogger().lifecycle(stripTrailing(output));
      }

      // Exit code 1 is overloaded: analyzer versions back to 1.33.0 use it both for
      // determinism violations after a completed scan and for pre-scan configuration
      // failures. Only code 1 paired with the stable completed-analysis summary is a
      // suppressible violation; every other nonzero exit is an execution failure.
      if (isViolationExit(result.getExitValue(), output)) {
        if (getFailOnViolation().get()) {
          throw new GradleException(
              "Workflow determinism violations found. See report: " + reportFile);
        }
      } else if (result.getExitValue() != 0) {
        throw new GradleException(
            "WorkflowCheck failed before completing analysis (exit code "
                + result.getExitValue()
                + "). See report: "
                + reportFile);
      }
    } catch (IOException e) {
      throw new GradleException("WorkflowCheck failed", e);
    }
  }

  static boolean isViolationExit(int exitValue, String output) {
    return exitValue == 1 && output.contains(COMPLETED_ANALYSIS_SUMMARY);
  }

  private static String joinClasspath(List<File> files) {
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < files.size(); i++) {
      if (i > 0) {
        builder.append(File.pathSeparatorChar);
      }
      builder.append(files.get(i).getAbsolutePath());
    }
    return builder.toString();
  }

  private static String stripTrailing(String value) {
    int end = value.length();
    while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) {
      end--;
    }
    return value.substring(0, end);
  }
}
