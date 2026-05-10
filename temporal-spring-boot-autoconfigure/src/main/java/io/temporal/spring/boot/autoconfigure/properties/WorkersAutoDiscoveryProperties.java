package io.temporal.spring.boot.autoconfigure.properties;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import org.springframework.boot.context.properties.ConstructorBinding;
import org.springframework.boot.context.properties.DeprecatedConfigurationProperty;

public class WorkersAutoDiscoveryProperties {
  /**
   * When {@code true}, enables auto-registration of all {@code @ActivityImpl}-,
   * {@code @NexusServiceImpl}-, and {@code @WorkflowImpl}-annotated classes that are Spring-managed
   * beans. This is the recommended option for simple use cases where all implementations are
   * Spring-managed beans. It implies {@link #registerActivityBeans} and {@link
   * #registerNexusServiceBeans} default to {@code true}, and also enables auto-registration of
   * {@code @WorkflowImpl}-annotated classes that are Spring-managed beans, without needing {@link
   * #workflowPackages}.
   *
   * <p>Classpath-scanning via {@link #workflowPackages} (for non-bean workflow classes) still
   * requires explicit package configuration regardless of this flag.
   */
  private final @Nullable Boolean enabled;

  private final @Nullable List<String> workflowPackages;
  private final @Nullable Boolean registerActivityBeans;
  private final @Nullable Boolean registerNexusServiceBeans;

  /**
   * @deprecated Use {@link #workflowPackages} instead. If set and non-empty, this property causes
   *     {@link #registerActivityBeans} and {@link #registerNexusServiceBeans} to default to {@code
   *     true}, and its entries to be considered as if they were provided through {@link
   *     #workflowPackages}. Setting both {@link #packages} and any of the other new properties is
   *     unsupported and will result in an exception.
   */
  @Deprecated private final @Nullable List<String> packages;

  @ConstructorBinding
  public WorkersAutoDiscoveryProperties(
      @Nullable Boolean enabled,
      @Nullable List<String> workflowPackages,
      @Nullable Boolean registerActivityBeans,
      @Nullable Boolean registerNexusServiceBeans,
      @Nullable List<String> packages) {
    if (packages != null
        && !packages.isEmpty()
        && (enabled != null
            || workflowPackages != null
            || registerActivityBeans != null
            || registerNexusServiceBeans != null)) {
      throw new IllegalStateException(
          "spring.temporal.workers-auto-discovery.packages is deprecated and cannot be combined "
              + "with enabled, workflow-packages, register-activity-beans, or register-nexus-service-beans. "
              + "Migrate to the new properties and remove packages.");
    }
    this.enabled = enabled;
    this.workflowPackages = workflowPackages;
    this.registerActivityBeans = registerActivityBeans;
    this.registerNexusServiceBeans = registerNexusServiceBeans;
    this.packages = packages;
  }

  /**
   * Returns whether {@code @WorkflowImpl}-annotated classes that are also Spring-managed beans
   * should be automatically registered with matching workers (without classpath scanning). Defaults
   * to {@code true} when {@link #enabled} is {@code true}, {@code false} otherwise. Workflow
   * implementation classes that are not Spring-managed beans still require {@link
   * #workflowPackages} for classpath scanning.
   */
  public boolean isRegisterWorkflowManagedBeans() {
    return Boolean.TRUE.equals(enabled);
  }

  /**
   * Returns whether {@code @ActivityImpl}-annotated beans should be automatically registered with
   * matching workers. Defaults to {@code true} when {@link #enabled} is {@code true} or when the
   * deprecated {@link #packages} property is set and non-empty; {@code false} otherwise.
   */
  public boolean isRegisterActivityBeans() {
    if (registerActivityBeans != null) return registerActivityBeans;
    return Boolean.TRUE.equals(enabled) || (packages != null && !packages.isEmpty());
  }

  /**
   * Returns whether {@code @NexusServiceImpl}-annotated beans should be automatically registered
   * with matching workers. Defaults to {@code true} when {@link #enabled} is {@code true} or when
   * the deprecated {@link #packages} property is set and non-empty; {@code false} otherwise.
   */
  public boolean isRegisterNexusServiceBeans() {
    if (registerNexusServiceBeans != null) return registerNexusServiceBeans;
    return Boolean.TRUE.equals(enabled) || (packages != null && !packages.isEmpty());
  }

  /**
   * Returns the list of packages to scan for {@code @WorkflowImpl} classes. When the deprecated
   * {@link #packages} property is set, its entries are used; otherwise returns the entries of
   * {@link #workflowPackages}. The two properties cannot be set simultaneously.
   */
  public List<String> getEffectiveWorkflowPackages() {
    List<String> result = new ArrayList<>();
    if (packages != null) result.addAll(packages);
    if (workflowPackages != null) result.addAll(workflowPackages);
    return result;
  }

  @Nullable
  public List<String> getWorkflowPackages() {
    return workflowPackages;
  }

  /**
   * @deprecated `packages` has unclear semantics with regard to registration of activity and nexus
   *     service beans; use {@link #getWorkflowPackages()} instead.
   */
  @Deprecated
  @DeprecatedConfigurationProperty(
      replacement = "spring.temporal.workers-auto-discovery.workflow-packages",
      reason =
          "'packages' has unclear semantics with regard to registration of activity and nexus service beans; use 'workflow-packages' instead")
  @Nullable
  public List<String> getPackages() {
    return packages;
  }
}
