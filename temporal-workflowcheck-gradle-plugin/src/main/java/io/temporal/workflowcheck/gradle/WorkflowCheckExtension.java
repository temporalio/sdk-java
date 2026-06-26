package io.temporal.workflowcheck.gradle;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/** Configuration for the {@code workflowCheck} task. */
public abstract class WorkflowCheckExtension {

  public static final String NAME = "workflowCheck";

  /** Source set names to check. Default: {@code ["main"]}. */
  public abstract ListProperty<String> getSourceSets();

  /** Additional {@code .properties} config files merged on top of defaults. */
  public abstract ConfigurableFileCollection getConfigFiles();

  /** Fail the build on violations. Default: {@code true}. */
  public abstract Property<Boolean> getFailOnViolation();

  /** Show valid workflow methods alongside invalid ones. Default: {@code false}. */
  public abstract Property<Boolean> getShowValid();

  /** Skip the default bundled workflowcheck config. Default: {@code false}. */
  public abstract Property<Boolean> getNoDefaultConfig();

  /** Override the ASM version used by temporal-workflowcheck. */
  public abstract Property<String> getAsmVersion();

  /** Max heap size for the forked JVM. Default: unset, which uses JVM ergonomics. */
  public abstract Property<String> getMaxHeapSize();
}
