package io.temporal.workflow;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that the method is a query method. Query method can be used to query a workflow state
 * by external process at any time during its execution. This annotation applies only to workflow
 * interface methods.
 *
 * <p>Query methods must never change any workflow state including starting activities or block
 * threads in any way.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface QueryMethod {
  /**
   * Name of the query type. Default is method name.
   *
   * <p>Be careful about names that contain special characters. These names can be used as metric
   * tags. And systems like prometheus ignore metrics which have tags with unsupported characters.
   *
   * <p>Name cannot start with __temporal as it is reserved for internal use. The name also cannot
   * be __stack_trace or __enhanced_stack_trace as they are reserved for internal use.
   */
  String name() default "";

  /** Short description of the query type. Default is an empty string. */
  String description() default "";
}
