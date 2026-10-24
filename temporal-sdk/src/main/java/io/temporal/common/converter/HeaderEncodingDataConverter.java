package io.temporal.common.converter;

import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.failure.v1.Failure;
import io.temporal.payload.codec.ChainCodec;
import io.temporal.payload.codec.HeaderCodecProvider;
import io.temporal.payload.codec.PayloadCodec;
import io.temporal.payload.context.SerializationContext;
import java.lang.reflect.Type;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nonnull;

/**
 * Opts into automatic codec-only processing of payload headers, including tracing and context
 * propagation. Configure this as the outermost data converter on workflow, activity, or schedule
 * clients. Workers inherit their workflow client's converter.
 *
 * <p>Supply serialized, not already codec-encoded, header payloads. Inbound interceptors and
 * propagators receive decoded payloads. Nexus string headers and gRPC metadata are unaffected.
 * Before enabling this for existing histories or schedules, ensure the decoder accepts legacy
 * plaintext headers. Codec failures never fall back to plaintext.
 *
 * <p>SDK-owned transfer and storage wrappers forward {@link HeaderCodecProvider}. Custom wrappers
 * must forward that capability or keep this wrapper outermost. An opaque outer wrapper can hide the
 * opt-in. Normal value and failure conversion are delegated unchanged.
 */
public final class HeaderEncodingDataConverter implements DataConverter, HeaderCodecProvider {
  private final DataConverter delegate;
  private final PayloadCodec headerCodec;

  /**
   * @param delegate value converter with an exposed codec-only stage
   * @throws IllegalArgumentException if the codec stage is missing or empty
   */
  public HeaderEncodingDataConverter(DataConverterWithCodec delegate) {
    this(Objects.requireNonNull(delegate, "delegate"), delegate.getPayloadCodec());
  }

  private HeaderEncodingDataConverter(DataConverter delegate, PayloadCodec codec) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    if (codec == null) {
      throw new IllegalArgumentException("HeaderEncodingDataConverter requires a nonempty codec");
    }
    this.headerCodec = codec instanceof ChainCodec ? ((ChainCodec) codec).forHeaders() : codec;
  }

  @Override
  public PayloadCodec getHeaderCodec() {
    return headerCodec;
  }

  @Override
  public <T> Optional<Payload> toPayload(T value) {
    return delegate.toPayload(value);
  }

  @Override
  public <T> T fromPayload(Payload payload, Class<T> valueClass, Type valueType) {
    return delegate.fromPayload(payload, valueClass, valueType);
  }

  @Override
  public Optional<Payloads> toPayloads(Object... values) {
    return delegate.toPayloads(values);
  }

  @Override
  public <T> T fromPayloads(
      int index, Optional<Payloads> content, Class<T> valueType, Type valueGenericType) {
    return delegate.fromPayloads(index, content, valueType, valueGenericType);
  }

  @Override
  public Object[] fromPayloads(
      Optional<Payloads> content, Class<?>[] parameterTypes, Type[] genericParameterTypes) {
    return delegate.fromPayloads(content, parameterTypes, genericParameterTypes);
  }

  @Nonnull
  @Override
  public Failure exceptionToFailure(@Nonnull Throwable throwable) {
    return delegate.exceptionToFailure(throwable);
  }

  @Nonnull
  @Override
  public RuntimeException failureToException(@Nonnull Failure failure) {
    return delegate.failureToException(failure);
  }

  @Nonnull
  @Override
  public HeaderEncodingDataConverter withContext(@Nonnull SerializationContext context) {
    return new HeaderEncodingDataConverter(
        delegate.withContext(context), headerCodec.withContext(context));
  }
}
