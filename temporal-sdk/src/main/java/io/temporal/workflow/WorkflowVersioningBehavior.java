package io.temporal.workflow;

import io.temporal.common.VersioningBehavior;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates the versioning behavior of this workflow. May only be applied to workflow
 * implementations, not interfaces.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface WorkflowVersioningBehavior {
  /**
   * The behavior to apply to this workflow. See {@link VersioningBehavior} for more information.
   */
  VersioningBehavior value();
}
