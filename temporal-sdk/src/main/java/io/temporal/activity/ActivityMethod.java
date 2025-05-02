package io.temporal.activity;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that the method is an Activity method. This annotation applies only to Activity
 * interface methods. Use it to override default Activity type name. Not required.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ActivityMethod {

  /**
   * Represents the name of the Activity type. Default value is the method's name, with the first
   * letter capitalized. Also consider using {@link ActivityInterface#namePrefix}. The prefix is
   * ignored if the name is specified.
   *
   * <p>Be careful with names that contain special characters, as these names can be used as metric
   * tags. Systems like Prometheus ignore metrics which have tags with unsupported characters.
   *
   * <p>Name cannot start with __temporal_ as it is reserved for internal use.
   */
  String name() default "";
}
