package io.temporal.workflowcheck.gradle;

import java.util.Collections;
import java.util.stream.Collectors;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;

/** Gradle plugin that runs the temporal-workflowcheck bytecode analyzer. */
public class WorkflowCheckPlugin implements Plugin<Project> {

  public static final String CONFIGURATION_NAME = "workflowcheck";

  @Override
  public void apply(Project project) {
    project
        .getPluginManager()
        .withPlugin(
            "java",
            appliedPlugin -> {
              WorkflowCheckExtension extension =
                  project
                      .getExtensions()
                      .create(WorkflowCheckExtension.NAME, WorkflowCheckExtension.class);

              extension.getSourceSets().convention(Collections.singletonList("main"));
              extension.getFailOnViolation().convention(true);
              extension.getShowValid().convention(false);
              extension.getNoDefaultConfig().convention(false);

              Configuration toolClasspath =
                  project
                      .getConfigurations()
                      .create(
                          CONFIGURATION_NAME,
                          configuration -> {
                            configuration.setCanBeConsumed(false);
                            configuration.setCanBeResolved(true);
                            configuration.setVisible(false);
                            configuration.setDescription(
                                "Classpath for the temporal-workflowcheck bytecode analyzer");
                          });

              SourceSetContainer sourceSets =
                  project.getExtensions().getByType(SourceSetContainer.class);

              toolClasspath.defaultDependencies(
                  dependencies -> {
                    String sdkVersion =
                        findTemporalSdkVersion(
                            project, sourceSets, extension.getSourceSets().get());
                    if (sdkVersion != null) {
                      dependencies.add(
                          project
                              .getDependencies()
                              .create("io.temporal:temporal-workflowcheck:" + sdkVersion));
                    }
                  });

              toolClasspath
                  .getResolutionStrategy()
                  .eachDependency(
                      details -> {
                        if ("org.ow2.asm".equals(details.getRequested().getGroup())
                            && extension.getAsmVersion().isPresent()) {
                          details.useVersion(extension.getAsmVersion().get());
                          details.because(
                              "Overridden by workflowCheck.asmVersion for JDK compatibility");
                        }
                      });

              TaskProvider<WorkflowCheckTask> workflowCheckTask =
                  project
                      .getTasks()
                      .register(
                          WorkflowCheckTask.TASK_NAME,
                          WorkflowCheckTask.class,
                          task -> {
                            task.setDescription(
                                "Checks Temporal workflow code for determinism violations");
                            task.setGroup("verification");

                            task.getToolClasspath().from(toolClasspath);
                            task.getClasspath()
                                .from(
                                    extension
                                        .getSourceSets()
                                        .map(
                                            names ->
                                                names.stream()
                                                    .map(sourceSets::getByName)
                                                    .map(SourceSet::getRuntimeClasspath)
                                                    .collect(Collectors.toList())));
                            task.dependsOn(
                                extension
                                    .getSourceSets()
                                    .map(
                                        names ->
                                            names.stream()
                                                .map(sourceSets::getByName)
                                                .map(SourceSet::getClassesTaskName)
                                                .collect(Collectors.toList())));

                            task.getConfigFiles().from(extension.getConfigFiles());
                            task.getFailOnViolation().set(extension.getFailOnViolation());
                            task.getShowValid().set(extension.getShowValid());
                            task.getNoDefaultConfig().set(extension.getNoDefaultConfig());
                            task.getMaxHeapSize().set(extension.getMaxHeapSize());
                            task.getReportFile()
                                .set(
                                    project
                                        .getLayout()
                                        .getBuildDirectory()
                                        .file("reports/workflowcheck/report.txt"));
                          });

              project
                  .getTasks()
                  .named("check")
                  .configure(check -> check.dependsOn(workflowCheckTask));
            });
  }

  /**
   * Walks resolved runtime dependencies for the configured source sets to find {@code
   * io.temporal:temporal-sdk} and returns its version.
   */
  static String findTemporalSdkVersion(
      Project project, SourceSetContainer sourceSets, Iterable<String> sourceSetNames) {
    for (String sourceSetName : sourceSetNames) {
      try {
        SourceSet sourceSet = sourceSets.getByName(sourceSetName);
        Configuration runtimeClasspath =
            project.getConfigurations().getByName(sourceSet.getRuntimeClasspathConfigurationName());
        for (ResolvedDependency dependency :
            runtimeClasspath.getResolvedConfiguration().getFirstLevelModuleDependencies()) {
          String version = findSdkVersionRecursive(dependency);
          if (version != null) {
            return version;
          }
        }
      } catch (Exception e) {
        project
            .getLogger()
            .warn(
                "WorkflowCheck: Failed to resolve Temporal SDK version from source set {}: {}",
                sourceSetName,
                e.getMessage());
      }
    }
    project
        .getLogger()
        .warn(
            "WorkflowCheck: No io.temporal:temporal-sdk found on configured source set runtime classpaths. Task will be skipped.");
    return null;
  }

  private static String findSdkVersionRecursive(ResolvedDependency dependency) {
    ModuleVersionIdentifier id = dependency.getModule().getId();
    if ("io.temporal".equals(id.getGroup()) && "temporal-sdk".equals(id.getName())) {
      return id.getVersion();
    }
    for (ResolvedDependency child : dependency.getChildren()) {
      String found = findSdkVersionRecursive(child);
      if (found != null) {
        return found;
      }
    }
    return null;
  }
}
