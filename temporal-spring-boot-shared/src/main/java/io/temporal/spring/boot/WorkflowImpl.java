package io.temporal.spring.boot;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enables the Workflow implementation class to be discovered by the Workers auto-discovery. This
 * annotation is not needed if only an explicit config is used.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface WorkflowImpl {
  /**
   * @return names of Workers to register this workflow implementation with. Workers with these
   *     names must be present in the application config or auto-discovered from {@link
   *     #taskQueues()}. Worker is named by its task queue if its name is not specified.
   */
  String[] workers() default {};

  /**
   * @return Worker Task Queues to register this workflow implementation with. If Worker with the
   *     specified Task Queue is not defined in the application config, it will be created with a
   *     default config. Can be specified by a property key, e.g.: ${propertyKey}.
   */
  String[] taskQueues() default {};
}
