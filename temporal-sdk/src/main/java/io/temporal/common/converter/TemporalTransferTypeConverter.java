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
 * applied to top-level values only and performs one transfer step. The configured {@link
 * DataConverter} remains responsible for serialization, payload codecs, and wire encoding.
 *
 * <p>The converter class must be concrete and have a public no-argument constructor. Converter
 * instances are cached, so implementations must be stateless and thread-safe.
 */
@Experimental
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface TemporalTransferTypeConverter {
  /** The converter associated with the annotated model class. */
  Class<? extends TransferTypeConverter<?>> value();
}
