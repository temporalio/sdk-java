package io.temporal.payload.codec;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.Experimental;
import io.temporal.common.converter.DataConverter;
import io.temporal.payload.context.SerializationContext;
import java.util.List;
import javax.annotation.Nonnull;

/**
 * Codec that encodes or decodes the given payloads. {@link PayloadCodec} implementation may be used
 * in {@link io.temporal.common.converter.CodecDataConverter} or server side in a Remote Data
 * Encoder implementation.
 */
public interface PayloadCodec {
  @Nonnull
  List<Payload> encode(@Nonnull List<Payload> payloads);

  @Nonnull
  List<Payload> decode(@Nonnull List<Payload> payloads);

  /**
   * A correct implementation of this interface should have a fully functional "contextless"
   * implementation. Temporal SDK will call this method when a knowledge of the context exists, but
   * {@link DataConverter} can be used directly by user code and sometimes SDK itself without any
   * context.
   *
   * <p>Note: this method is expected to be cheap and fast. Temporal SDK doesn't always cache the
   * instances and may be calling this method very often. Users are responsible to make sure that
   * this method doesn't recreate expensive objects like Jackson's {@link ObjectMapper} on every
   * call.
   *
   * @param context provides information to the data converter about the abstraction the data
   *     belongs to
   * @return an instance of DataConverter that may use the provided {@code context} for
   *     serialization
   * @see SerializationContext
   */
  @Experimental
  @Nonnull
  default PayloadCodec withContext(@Nonnull SerializationContext context) {
    return this;
  }
}
