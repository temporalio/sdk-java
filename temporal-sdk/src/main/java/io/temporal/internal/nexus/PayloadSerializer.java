package io.temporal.internal.nexus;

import com.google.protobuf.InvalidProtocolBufferException;
import io.nexusrpc.Serializer;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.DataConverter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * PayloadSerializer is a serializer that converts objects to and from {@link
 * io.nexusrpc.Serializer.Content} objects by using the {@link DataConverter} to convert objects to
 * and from {@link Payload} objects.
 */
class PayloadSerializer implements Serializer {
  DataConverter dataConverter;

  PayloadSerializer(DataConverter dataConverter) {
    this.dataConverter = dataConverter;
  }

  @Override
  public Content serialize(@Nullable Object o) {
    Optional<Payload> payload = dataConverter.toPayload(o);
    Content.Builder content = Content.newBuilder();
    content.setData(payload.get().toByteArray());
    return content.build();
  }

  @Override
  public @Nullable Object deserialize(Content content, Type type) {
    try {
      Payload payload = Payload.parseFrom(content.getData());
      if ((type instanceof Class)) {
        return dataConverter.fromPayload(payload, (Class<?>) type, type);
      } else if (type instanceof ParameterizedType) {
        return dataConverter.fromPayload(
            payload, (Class<?>) ((ParameterizedType) type).getRawType(), type);
      } else {
        throw new IllegalArgumentException("Unsupported type: " + type);
      }
    } catch (InvalidProtocolBufferException e) {
      throw new RuntimeException(e);
    }
  }
}
