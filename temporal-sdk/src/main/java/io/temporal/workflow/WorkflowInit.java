package io.temporal.workflow;

import io.temporal.common.Experimental;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that the constructor should be used as a workflow initialization method. The method
 * annotated with this annotation is called when a new workflow instance is created. The method must
 * be public and take no arguments or take the same arguments as the workflow method. All the same
 * constraints as for workflow methods apply to workflow initialization methods.
 *
 * <p>This annotation applies only to workflow implementation constructors.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.CONSTRUCTOR)
@Experimental
public @interface WorkflowInit {}
