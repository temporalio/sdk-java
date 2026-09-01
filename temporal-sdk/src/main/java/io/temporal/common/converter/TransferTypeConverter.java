package io.temporal.common.converter;

import io.temporal.common.Experimental;
import java.lang.reflect.Type;

/**
 * Converts a model to and from a representation handled by the configured {@link DataConverter}.
 *
 * <p>Conversion is applied only to top-level values and performs one transfer step. The {@code
 * valueType} arguments contain the complete requested model type, including generic arguments.
 * Implementations must be stateless and thread-safe because converter instances are cached.
 *
 * @param <T> annotated model type converted to and from its transfer representation
 */
@Experimental
public interface TransferTypeConverter<T> {
  /**
   * Returns the type used to serialize a model value.
   *
   * @param valueType complete declared model type, including generic arguments
   * @return non-null transfer type
   */
  Type getTransferType(Type valueType);

  /** Converts a model value to its transfer representation. */
  Object toTransferType(T value);

  /**
   * Reconstructs a model value from its transfer representation.
   *
   * @param value transfer representation decoded by the configured data converter
   * @param valueType complete requested model type, including generic arguments
   */
  T fromTransferType(Object value, Type valueType);
}
