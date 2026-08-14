package io.temporal.internal.nexus;

import com.google.protobuf.InvalidProtocolBufferException;
import io.nexusrpc.Serializer;
import io.nexusrpc.handler.HandlerException;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DataConverterException;
import io.temporal.failure.ApplicationFailure;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * PayloadSerializer is a serializer that converts objects to and from {@link
 * io.nexusrpc.Serializer.Content} objects by using the {@link DataConverter} to convert objects to
 * and from {@link Payload} objects.
 *
 * <p>Nexus propagates serializer failures as-is, so the error type and retry behavior a handler
 * reports for them is decided here.
 *
 * <p>Input that will never decode into the expected type is the caller's fault and is reported as a
 * non-retryable {@link HandlerException.ErrorType#BAD_REQUEST}. A data converter can also opt into
 * that treatment for input it decoded but rejected, by throwing a non-retryable {@link
 * ApplicationFailure} of type {@value #PAYLOAD_VALIDATION_ERROR_TYPE}. Any other failure on the way
 * to the value, a {@link io.temporal.payload.codec.PayloadCodec} outage for example, may well
 * succeed on a retry, so it is left to the handling in {@link NexusTaskHandlerImpl} that a failure
 * from an operation handler would get.
 *
 * <p>Serializing an operation result is not translated at all. Note that this still means a
 * converter can choose the outcome: a non-retryable {@link ApplicationFailure} raised while
 * serializing a result becomes a non-retryable {@code INTERNAL} handler error by way of {@link
 * NexusTaskHandlerImpl}, and anything else keeps the retryable {@code INTERNAL} default.
 */
class PayloadSerializer implements Serializer {
  /**
   * {@link ApplicationFailure#getType()} a data converter uses to say it understood the input but
   * considers it invalid. When non-retryable, it is reported as {@link
   * HandlerException.ErrorType#BAD_REQUEST} rather than as a handler-side {@code INTERNAL} error.
   */
  static final String PAYLOAD_VALIDATION_ERROR_TYPE = "PayloadValidationError";

  private final DataConverter dataConverter;

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
        // A problem with the operation definition rather than with the request, but no amount of
        // retrying will introduce support for the type.
        throw new HandlerException(
            HandlerException.ErrorType.INTERNAL,
            "Unsupported operation input type: " + type,
            null,
            HandlerException.RetryBehavior.NON_RETRYABLE);
      }
    } catch (ApplicationFailure e) {
      if (e.isNonRetryable() && PAYLOAD_VALIDATION_ERROR_TYPE.equals(e.getType())) {
        // The data converter decoded the input and rejected it, so this is the caller's fault
        // rather than a handler-side error.
        throw new HandlerException(
            HandlerException.ErrorType.BAD_REQUEST, "invalid operation input", e);
      }
      // Otherwise the data converter already picked an error type and retry behavior, keep them.
      throw e;
    } catch (HandlerException e) {
      // The data converter already picked an error type and retry behavior, keep them.
      throw e;
    } catch (InvalidProtocolBufferException | DataConverterException e) {
      // These bytes will not become this type on a retry. Everything else propagates, so a
      // transient failure such as a payload codec outage stays retryable.
      throw new HandlerException(
          HandlerException.ErrorType.BAD_REQUEST, "failed to deserialize input", e);
    }
  }
}
