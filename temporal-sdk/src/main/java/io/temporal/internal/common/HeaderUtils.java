package io.temporal.internal.common;

import com.google.common.base.Suppliers;
import io.temporal.api.common.v1.Header;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DataConverterException;
import io.temporal.payload.codec.PayloadCodec;
import io.temporal.payload.codec.PayloadCodecException;
import io.temporal.payload.codec.PayloadCodecProvider;
import io.temporal.payload.context.SerializationContext;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.annotation.Nullable;

public class HeaderUtils {

  public static Header toHeaderGrpc(
      io.temporal.common.interceptors.Header header,
      @Nullable io.temporal.common.interceptors.Header overrides) {
    return toHeaderGrpc(header, overrides, null);
  }

  public static Header toHeaderGrpc(
      io.temporal.common.interceptors.Header header,
      @Nullable io.temporal.common.interceptors.Header overrides,
      @Nullable PayloadCodec codec) {
    Header.Builder result = Header.newBuilder();
    Header.Builder selected = Header.newBuilder();
    for (io.temporal.common.interceptors.Header source :
        new io.temporal.common.interceptors.Header[] {header, overrides}) {
      if (source == null) {
        continue;
      }
      for (Map.Entry<String, Payload> entry : source.getValues().entrySet()) {
        result.putFields(entry.getKey(), entry.getValue());
        selected.removeFields(entry.getKey());
        if (source.shouldEncode(entry.getKey())) {
          selected.putFields(entry.getKey(), entry.getValue());
        }
      }
    }
    result.putAllFields(encode(selected.build(), codec).getFieldsMap());
    return result.build();
  }

  public static io.temporal.common.interceptors.Header fromContextPropagators(
      @Nullable List<ContextPropagator> propagators) {
    io.temporal.common.interceptors.Header result = io.temporal.common.interceptors.Header.empty();
    if (propagators != null) {
      for (ContextPropagator propagator : propagators) {
        result.putAll(
            propagator.serializeContext(propagator.getCurrentContext()),
            propagator.encodeHeaders());
      }
    }
    return result;
  }

  public static Header encode(Header header, @Nullable PayloadCodec codec) {
    return transform(header, codec, true);
  }

  public static Header decode(Header header, @Nullable PayloadCodec codec) {
    return transform(header, codec, false);
  }

  @Nullable
  public static PayloadCodec resolveHeaderCodec(DataConverter converter) {
    return converter instanceof PayloadCodecProvider
        ? ((PayloadCodecProvider) converter).getPayloadCodec()
        : null;
  }

  @Nullable
  public static PayloadCodec withContext(
      @Nullable PayloadCodec codec, SerializationContext context) {
    if (codec == null) {
      return null;
    }
    // Binding must not inspect or reject codec stages for producers that did not opt in.
    Supplier<PayloadCodec> bound =
        Suppliers.memoize(
            () -> {
              PayloadCodec contextual = codec.withContext(context);
              if (contextual == null) {
                throw new IllegalArgumentException("Header codec binding must not return null");
              }
              return contextual;
            });
    return new PayloadCodec() {
      @Override
      public List<Payload> encode(List<Payload> payloads) {
        return bound.get().encode(payloads);
      }

      @Override
      public List<Payload> decode(List<Payload> payloads) {
        return bound.get().decode(payloads);
      }
    };
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
