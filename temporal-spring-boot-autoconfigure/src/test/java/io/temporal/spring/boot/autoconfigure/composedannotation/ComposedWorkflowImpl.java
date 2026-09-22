package io.temporal.spring.boot.autoconfigure.composedannotation;

import io.temporal.spring.boot.WorkflowImpl;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@WorkflowImpl(taskQueues = "UnitTest")
public @interface ComposedWorkflowImpl {}
