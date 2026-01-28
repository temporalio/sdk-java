package io.temporal.common.converter;

import com.google.protobuf.MessageLite;
import io.temporal.api.common.v1.Payload;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.util.Optional;

public final class ProtobufPayloadConverter extends AbstractProtobufPayloadConverter
    implements PayloadConverter {

  public ProtobufPayloadConverter() {
    super();
  }

  public ProtobufPayloadConverter(boolean excludeProtobufMessageTypes) {
    super(excludeProtobufMessageTypes);
  }

  @Override
  public String getEncodingType() {
    return EncodingKeys.METADATA_ENCODING_PROTOBUF_NAME;
  }

  @Override
  public Optional<Payload> toData(Object value) throws DataConverterException {
    if (!(value instanceof MessageLite)) {
      return Optional.empty();
    }
    try {
      Payload.Builder builder =
          Payload.newBuilder()
              .putMetadata(
                  EncodingKeys.METADATA_ENCODING_KEY, EncodingKeys.METADATA_ENCODING_PROTOBUF)
              .setData(((MessageLite) value).toByteString());
      super.addMessageType(builder, value);
      return Optional.of(builder.build());
    } catch (Exception e) {
      throw new DataConverterException(e);
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> T fromData(Payload content, Class<T> valueClass, Type valueType)
      throws DataConverterException {
    if (!MessageLite.class.isAssignableFrom(valueClass)) {
      throw new IllegalArgumentException("Not a protobuf. valueClass=" + valueClass.getName());
    }
    try {
      Method parseFrom = valueClass.getMethod("parseFrom", ByteBuffer.class);
      Object instance = parseFrom.invoke(null, content.getData().asReadOnlyByteBuffer());
      super.checkMessageType(content, instance);
      return (T) instance;
    } catch (Exception e) {
      throw new DataConverterException(e);
    }
  }
}
