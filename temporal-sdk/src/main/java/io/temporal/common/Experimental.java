package io.temporal.common;

import java.lang.annotation.*;

/**
 * Annotation that specifies that an element is experimental, has unstable API or may change without
 * notice. This annotation is inherited.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.TYPE, ElementType.METHOD})
public @interface Experimental {}
