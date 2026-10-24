package io.temporal.internal.common;

import io.temporal.api.common.v1.Header;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DataConverterException;
import io.temporal.payload.codec.ChainCodec;
import io.temporal.payload.codec.HeaderCodecProvider;
import io.temporal.payload.codec.PayloadCodec;
import io.temporal.payload.codec.PayloadCodecException;
import io.temporal.payload.context.SerializationContext;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

public class HeaderUtils {

  public static Header toHeaderGrpc(
      io.temporal.common.interceptors.Header header,
      @Nullable io.temporal.common.interceptors.Header overrides) {
    Header.Builder builder = Header.newBuilder().putAllFields(header.getValues());
    if (overrides != null) {
      for (Map.Entry<String, Payload> item : overrides.getValues().entrySet()) {
        builder.putFields(item.getKey(), item.getValue());
      }
    }
    return builder.build();
  }

  public static Header toHeaderGrpc(
      io.temporal.common.interceptors.Header header,
      @Nullable io.temporal.common.interceptors.Header overrides,
      @Nullable PayloadCodec codec) {
    return encode(toHeaderGrpc(header, overrides), codec);
  }

  public static Header encode(Header header, @Nullable PayloadCodec codec) {
    return transform(header, codec, true);
  }

  public static Header decode(Header header, @Nullable PayloadCodec codec) {
    return transform(header, codec, false);
  }

  @Nullable
  public static PayloadCodec getHeaderCodec(DataConverter converter) {
    return converter instanceof HeaderCodecProvider
        ? ((HeaderCodecProvider) converter).getHeaderCodec()
        : null;
  }

  @Nullable
  public static PayloadCodec resolveHeaderCodec(DataConverter converter) {
    PayloadCodec codec = getHeaderCodec(converter);
    return codec == null ? null : requireHeaderCodec(codec);
  }

  private static PayloadCodec requireHeaderCodec(@Nullable PayloadCodec codec) {
    if (codec == null) {
      throw new IllegalArgumentException("Header codec binding must not return null");
    }
    return codec instanceof ChainCodec ? ((ChainCodec) codec).forHeaders() : codec;
  }

  @Nullable
  public static PayloadCodec withContext(
      @Nullable PayloadCodec codec, SerializationContext context) {
    return codec == null ? null : requireHeaderCodec(codec.withContext(context));
  }

  private static Header transform(Header header, @Nullable PayloadCodec codec, boolean encode) {
    if (codec == null || header.getFieldsCount() == 0) {
      return header;
    }
    Header.Builder result = Header.newBuilder();
    for (Map.Entry<String, Payload> entry : header.getFieldsMap().entrySet()) {
      List<Payload> input = Collections.singletonList(entry.getValue());
      List<Payload> output = encode ? codec.encode(input) : codec.decode(input);
      if (output.size() != 1) {
        throw new PayloadCodecException(
            "Header codec must return one payload for key " + entry.getKey());
      }
      result.putFields(entry.getKey(), output.get(0));
    }
    return result.build();
  }

  /**
   * Converts a {@code Map<String, Object>} into a {@code Map<String, Payload>} by applying
   * specified converter on each value. This util should be used for things like search attributes
   * and memo that need to be converted back from bytes on the server.
   */
  public static Map<String, Payload> intoPayloadMap(
      DataConverter converter, Map<String, Object> map) {
    if (map == null) return null;
    Map<String, Payload> result = new HashMap<>();
    for (Map.Entry<String, Object> item : map.entrySet()) {
      try {
        result.put(item.getKey(), converter.toPayload(item.getValue()).get());
      } catch (DataConverterException e) {
        throw new DataConverterException("Cannot serialize key " + item.getKey(), e.getCause());
      }
    }
    return result;
  }

  private HeaderUtils() {}
}
