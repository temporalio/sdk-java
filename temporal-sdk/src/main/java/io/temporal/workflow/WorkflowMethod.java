package io.temporal.workflow;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that the method is a workflow method. Workflow method is executed when workflow is
 * started. Workflow completes when workflow method returns. This annotation applies only to
 * workflow interface methods.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface WorkflowMethod {
  /**
   * Name of the workflow type. Default is {short class name}.
   *
   * <p>Be careful with names that contain special characters, as these names can be used as metric
   * tags. Systems like Prometheus ignore metrics which have tags with unsupported characters.
   *
   * <p>Name cannot start with __temporal_ as it is reserved for internal use.
   */
  String name() default "";
}
