package io.temporal.workflow;

import io.temporal.common.Experimental;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that the constructor should be used as a workflow initialization method. The
 * constructor annotated with this annotation is called when a new workflow instance is created. The
 * method must be public and take the same arguments as the workflow method. All the same
 * constraints as for workflow methods apply to workflow initialization methods. Any exceptions
 * thrown by the constructor are treated the same as exceptions thrown by the workflow method.
 *
 * <p>Workflow initialization methods are called before the workflow method, signal handlers, update
 * handlers or query handlers. Users should be careful not to block in constructors. Blocking in the
 * constructor will delay handling of the workflow method and signal handlers, and cause rejection
 * of the update and query handlers.
 *
 * <p>This annotation applies only to workflow implementation constructors.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.CONSTRUCTOR)
@Experimental
public @interface WorkflowInit {}
