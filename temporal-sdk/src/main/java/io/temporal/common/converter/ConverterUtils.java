package io.temporal.common.converter;

import io.temporal.payload.codec.PayloadCodec;
import io.temporal.payload.context.SerializationContext;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

class ConverterUtils {
  static DataConverter withContext(
      @Nonnull DataConverter converter, @Nullable SerializationContext context) {
    return context != null ? converter.withContext(context) : converter;
  }

  static PayloadCodec withContext(
      @Nonnull PayloadCodec codec, @Nullable SerializationContext context) {
    return context != null ? codec.withContext(context) : codec;
  }
}
