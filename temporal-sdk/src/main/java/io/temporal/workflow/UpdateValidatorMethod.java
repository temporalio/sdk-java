package io.temporal.workflow;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import javax.annotation.Nonnull;

/**
 * Indicates that the method is an update validator handle. An update validator handle is associated
 * with an update method and runs before the associated update handle. If the update validator
 * throws an exception, the update handle is not called and the update is not persisted in history.
 *
 * <p>This annotation applies only to workflow interface methods.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface UpdateValidatorMethod {

  /**
   * Name of the update handler the validator should be used for. Must not be null.
   *
   * <p>Be careful about names that contain special characters. These names can be used as metric
   * tags. And systems like prometheus ignore metrics which have tags with unsupported characters.
   */
  @Nonnull
  String updateName();
}
