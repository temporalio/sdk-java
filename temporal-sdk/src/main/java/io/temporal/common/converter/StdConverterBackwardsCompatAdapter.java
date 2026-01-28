package io.temporal.common.converter;

import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import java.lang.reflect.Type;
import java.util.Optional;

/**
 * Temporal SDK should use Standard data converter for fields needed for internal essential
 * functionality. See comments on {@link DefaultDataConverter#STANDARD_INSTANCE}
 *
 * <p>Unfortunately, bad code hygiene and overload of the word "default" with several meanings in
 * DefaultDataConverter led to a situation where a user-defined converter may be used to serialize
 * these internal fields instead. This problem is solved now and all SDK internal fields are
 * serialized using Standard data converter. But users may already have workflows created by older
 * SDKs and overridden "default" (in meaning "global") converter containing payloads in internal
 * fields that must be deserialized using the user-defined converter.
 *
 * <p>This class provides a compatibility layer for deserialization of such internal fields by first
 * using {@link DefaultDataConverter#STANDARD_INSTANCE} and falling back to {@link
 * GlobalDataConverter#get()} in case of an exception.
 */
public class StdConverterBackwardsCompatAdapter {
  public static <T> T fromPayload(Payload payload, Class<T> valueClass, Type valueType) {
    try {
      return DefaultDataConverter.STANDARD_INSTANCE.fromPayload(payload, valueClass, valueType);
    } catch (DataConverterException e) {
      try {
        return GlobalDataConverter.get().fromPayload(payload, valueClass, valueType);
      } catch (DataConverterException legacyEx) {
        throw e;
      }
    }
  }

  public static <T> T fromPayloads(
      int index, Optional<Payloads> content, Class<T> valueType, Type valueGenericType)
      throws DataConverterException {
    try {
      return DefaultDataConverter.STANDARD_INSTANCE.fromPayloads(
          index, content, valueType, valueGenericType);
    } catch (DataConverterException e) {
      try {
        return GlobalDataConverter.get().fromPayloads(index, content, valueType, valueGenericType);
      } catch (DataConverterException legacyEx) {
        throw e;
      }
    }
  }
}
