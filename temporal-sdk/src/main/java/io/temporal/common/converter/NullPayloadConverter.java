package io.temporal.common.converter;

import io.temporal.api.common.v1.Payload;
import java.lang.reflect.Type;
import java.util.Optional;

/** Encodes and decodes null values. */
public final class NullPayloadConverter implements PayloadConverter {
  @Override
  public String getEncodingType() {
    return EncodingKeys.METADATA_ENCODING_NULL_NAME;
  }

  @Override
  public Optional<Payload> toData(Object value) throws DataConverterException {
    if (value == null) {
      return Optional.of(
          Payload.newBuilder()
              .putMetadata(EncodingKeys.METADATA_ENCODING_KEY, EncodingKeys.METADATA_ENCODING_NULL)
              .build());
    }
    return Optional.empty();
  }

  @Override
  public <T> T fromData(Payload content, Class<T> valueClass, Type valueType)
      throws DataConverterException {
    return null;
  }
}
