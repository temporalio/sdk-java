package io.temporal.spring.boot;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enables the Activity bean to be discovered by the Workers auto-discovery. This annotation is not
 * needed if only an explicit config is used.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface ActivityImpl {
  /**
   * @return names of Workers to register this activity bean with. Workers with these names must be
   *     present in the application config. Worker is named by its task queue if its name is not
   *     specified.
   */
  String[] workers() default {};

  /**
   * @return Worker Task Queues to register this activity bean with. If Worker with the specified
   *     Task Queue is not present in the application config, it will be created with a default
   *     config. Can be specified as a property key, e.g.: ${propertyKey}.
   */
  String[] taskQueues() default {};
}
