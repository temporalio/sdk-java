package io.temporal.common.converter;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import java.lang.reflect.Type;
import java.util.Optional;

public final class ByteArrayPayloadConverter implements PayloadConverter {
  @Override
  public String getEncodingType() {
    return EncodingKeys.METADATA_ENCODING_RAW_NAME;
  }

  @Override
  public Optional<Payload> toData(Object value) throws DataConverterException {
    if (!(value instanceof byte[])) {
      return Optional.empty();
    }
    return Optional.of(
        Payload.newBuilder()
            .putMetadata(EncodingKeys.METADATA_ENCODING_KEY, EncodingKeys.METADATA_ENCODING_RAW)
            .setData(ByteString.copyFrom((byte[]) value))
            .build());
  }

  @Override
  public <T> T fromData(Payload content, Class<T> valueClass, Type valueType)
      throws DataConverterException {
    ByteString data = content.getData();
    if (valueClass != byte[].class) {
      throw new IllegalArgumentException(
          "Raw encoding can be deserialized only to a byte array. valueClass="
              + valueClass.getName());
    }
    return (T) data.toByteArray();
  }
}
