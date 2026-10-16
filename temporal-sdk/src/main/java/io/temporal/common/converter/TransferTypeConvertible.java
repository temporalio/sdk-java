package io.temporal.common.converter;

import io.temporal.common.Experimental;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Associates a model class with the converter used to produce its transfer representation.
 *
 * <p>The annotation is read only from the exact declared class and is not inherited. Conversion is
 * applied to top-level values within a transfer-aware data converter. The configured {@link
 * DataConverter} remains responsible for serialization, payload codecs, and wire encoding.
 *
 * <p>The converter and its enclosing classes must be public. The converter must be concrete and
 * have a public no-argument constructor. Converter instances are cached, so implementations must be
 * stateless and thread-safe.
 */
@Experimental
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface TransferTypeConvertible {
  /** The converter associated with the annotated model class. */
  @SuppressWarnings("rawtypes")
  Class<? extends TransferTypeConverter> value();
}
