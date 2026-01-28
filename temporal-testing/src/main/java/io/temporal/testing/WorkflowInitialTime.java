package io.temporal.testing;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * JUnit5 only
 *
 * <p>Overrides the initial timestamp used by the {@link TestWorkflowExtension}
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface WorkflowInitialTime {

  String value();
}
