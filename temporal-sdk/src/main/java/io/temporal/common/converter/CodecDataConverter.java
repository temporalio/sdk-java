/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.common.converter;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.failure.v1.ApplicationFailureInfo;
import io.temporal.api.failure.v1.CanceledFailureInfo;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.failure.v1.ResetWorkflowFailureInfo;
import io.temporal.api.failure.v1.TimeoutFailureInfo;
import io.temporal.failure.TemporalFailure;
import io.temporal.payload.SerializationContext;
import io.temporal.payload.codec.ChainCodec;
import io.temporal.payload.codec.PayloadCodec;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A delegating {@link DataConverter} implementation that wraps and chains both another {@link
 * DataConverter} and several {@link PayloadCodec}s.
 *
 * <p>The underlying {@link DataConverter} is expected to be responsible for conversion between user
 * objects and bytes represented as {@link Payloads}, while the underlying chain of codecs is
 * responsible for a subsequent byte &lt;-&gt; byte manipulation such as encryption or compression
 */
public class CodecDataConverter implements DataConverter, PayloadCodec {
  private static final String ENCODED_FAILURE_MESSAGE = "Encoded failure";

  private final DataConverter dataConverter;
  private final ChainCodec chainCodec;
  private final boolean encodeFailureAttributes;
  private final @Nullable SerializationContext serializationContext;

  /**
   * When serializing to Payloads:
   *
   * <ul>
   *   <li>{@code dataConverter} is applied first, following by the chain of {@code codecs}.
   *   <li>{@code codecs} are applied last to first meaning the earlier encoders wrap the later ones
   * </ul>
   *
   * When deserializing from Payloads:
   *
   * <ul>
   *   <li>{@code codecs} are applied first to last to reverse the effect following by the {@code
   *       dataConverter}
   *   <li>{@code dataConverter} is applied last
   * </ul>
   *
   * See {@link #CodecDataConverter(DataConverter, Collection, boolean)} to enable encryption of
   * Failure attributes.
   *
   * @param dataConverter to delegate data conversion to
   * @param codecs to delegate bytes encoding/decoding to. When encoding, the codecs are applied
   *     last to first meaning the earlier encoders wrap the later ones. When decoding, the decoders
   *     are applied first to last to reverse the effect
   */
  public CodecDataConverter(DataConverter dataConverter, Collection<PayloadCodec> codecs) {
    this(dataConverter, codecs, false);
  }

  /**
   * When serializing to Payloads:
   *
   * <ul>
   *   <li>{@code dataConverter} is applied first, following by the chain of {@code codecs}.
   *   <li>{@code codecs} are applied last to first meaning the earlier encoders wrap the later ones
   * </ul>
   *
   * When deserializing from Payloads:
   *
   * <ul>
   *   <li>{@code codecs} are applied first to last to reverse the effect following by the {@code
   *       dataConverter}
   *   <li>{@code dataConverter} is applied last
   * </ul>
   *
   * Setting {@code encodeFailureAttributes} to true enables codec encoding of Failure attributes.
   * This can be used in conjunction with an encrypting codec to enable encryption of failures
   * message and stack traces. Note that failure's details are always codec-encoded, without regard
   * to {@code encodeFailureAttributes}.
   *
   * @param dataConverter to delegate data conversion to
   * @param codecs to delegate bytes encoding/decoding to. When encoding, the codecs are applied
   *     last to first meaning the earlier encoders wrap the later ones. When decoding, the decoders
   *     are applied first to last to reverse the effect
   * @param encodeFailureAttributes enable encoding of Failure attributes (message and stack trace)
   */
  public CodecDataConverter(
      DataConverter dataConverter,
      Collection<PayloadCodec> codecs,
      boolean encodeFailureAttributes) {
    this(dataConverter, new ChainCodec(codecs), encodeFailureAttributes, null);
  }

  CodecDataConverter(
      DataConverter dataConverter,
      ChainCodec codecs,
      boolean encodeFailureAttributes,
      @Nullable SerializationContext serializationContext) {
    this.dataConverter = dataConverter;
    this.chainCodec = codecs;
    this.encodeFailureAttributes = encodeFailureAttributes;
    this.serializationContext = serializationContext;
  }

  @Override
  public <T> Optional<Payload> toPayload(T value) {
    Optional<Payload> payload =
        ConverterUtils.withContext(dataConverter, serializationContext).toPayload(value);
    List<Payload> encodedPayloads =
        ConverterUtils.withContext(chainCodec, serializationContext)
            .encode(Collections.singletonList(payload.get()));
    Preconditions.checkState(encodedPayloads.size() == 1, "Expected one encoded payload");
    return Optional.of(encodedPayloads.get(0));
  }

  @Override
  public <T> T fromPayload(Payload payload, Class<T> valueClass, Type valueType) {
    List<Payload> decodedPayload =
        ConverterUtils.withContext(chainCodec, serializationContext)
            .decode(Collections.singletonList(payload));
    Preconditions.checkState(decodedPayload.size() == 1, "Expected one decoded payload");
    return ConverterUtils.withContext(dataConverter, serializationContext)
        .fromPayload(decodedPayload.get(0), valueClass, valueType);
  }

  @Override
  public Optional<Payloads> toPayloads(Object... values) throws DataConverterException {
    Optional<Payloads> payloads =
        ConverterUtils.withContext(dataConverter, serializationContext).toPayloads(values);
    if (payloads.isPresent()) {
      List<Payload> encodedPayloads =
          ConverterUtils.withContext(chainCodec, serializationContext)
              .encode(payloads.get().getPayloadsList());
      payloads = Optional.of(Payloads.newBuilder().addAllPayloads(encodedPayloads).build());
    }
    return payloads;
  }

  @Override
  public <T> T fromPayloads(
      int index, Optional<Payloads> content, Class<T> valueType, Type valueGenericType)
      throws DataConverterException {
    if (content.isPresent()) {
      content = Optional.of(decodePayloads(content.get()));
    }
    return ConverterUtils.withContext(dataConverter, serializationContext)
        .fromPayloads(index, content, valueType, valueGenericType);
  }

  @Override
  @Nonnull
  public Failure exceptionToFailure(@Nonnull Throwable throwable) {
    Preconditions.checkNotNull(throwable, "throwable");
    return this.encodeFailure(
            ConverterUtils.withContext(dataConverter, serializationContext)
                .exceptionToFailure(throwable)
                .toBuilder())
        .build();
  }

  @Override
  @Nonnull
  public TemporalFailure failureToException(@Nonnull Failure failure) {
    Preconditions.checkNotNull(failure, "failure");
    return ConverterUtils.withContext(dataConverter, serializationContext)
        .failureToException(this.decodeFailure(failure.toBuilder()).build());
  }

  @Nonnull
  @Override
  public CodecDataConverter withContext(@Nonnull SerializationContext context) {
    return new CodecDataConverter(dataConverter, chainCodec, encodeFailureAttributes, context);
  }

  @Nonnull
  @Override
  public List<Payload> encode(@Nonnull List<Payload> payloads) {
    return ConverterUtils.withContext(chainCodec, serializationContext).encode(payloads);
  }

  @Nonnull
  @Override
  public List<Payload> decode(@Nonnull List<Payload> payloads) {
    return ConverterUtils.withContext(chainCodec, serializationContext).decode(payloads);
  }

  private Failure.Builder encodeFailure(Failure.Builder failure) {
    if (failure.hasCause()) {
      failure.setCause(encodeFailure(failure.getCause().toBuilder()));
    }
    if (this.encodeFailureAttributes) {
      EncodedAttributes encodedAttributes = new EncodedAttributes();
      encodedAttributes.setStackTrace(failure.getStackTrace());
      encodedAttributes.setMessage(failure.getMessage());
      Payload encodedAttributesPayload = toPayload(Optional.of(encodedAttributes)).get();
      failure
          .setEncodedAttributes(encodedAttributesPayload)
          .setMessage(ENCODED_FAILURE_MESSAGE)
          .setStackTrace("");
    }
    switch (failure.getFailureInfoCase()) {
      case APPLICATION_FAILURE_INFO:
        {
          ApplicationFailureInfo.Builder info = failure.getApplicationFailureInfo().toBuilder();
          if (info.hasDetails()) {
            info.setDetails(encodePayloads(info.getDetails()));
          }
          failure.setApplicationFailureInfo(info);
        }
        break;
      case TIMEOUT_FAILURE_INFO:
        {
          TimeoutFailureInfo.Builder info = failure.getTimeoutFailureInfo().toBuilder();
          if (info.hasLastHeartbeatDetails()) {
            info.setLastHeartbeatDetails(encodePayloads(info.getLastHeartbeatDetails()));
          }
          failure.setTimeoutFailureInfo(info);
        }
        break;
      case CANCELED_FAILURE_INFO:
        {
          CanceledFailureInfo.Builder info = failure.getCanceledFailureInfo().toBuilder();
          if (info.hasDetails()) {
            info.setDetails(encodePayloads(info.getDetails()));
          }
          failure.setCanceledFailureInfo(info);
        }
        break;
      case RESET_WORKFLOW_FAILURE_INFO:
        {
          ResetWorkflowFailureInfo.Builder info = failure.getResetWorkflowFailureInfo().toBuilder();
          if (info.hasLastHeartbeatDetails()) {
            info.setLastHeartbeatDetails(encodePayloads(info.getLastHeartbeatDetails()));
          }
          failure.setResetWorkflowFailureInfo(info);
        }
        break;
      default:
        {
          // Other type of failure info don't have anything to encode
        }
    }
    return failure;
  }

  private Failure.Builder decodeFailure(Failure.Builder failure) {
    if (failure.hasCause()) {
      failure.setCause(decodeFailure(failure.getCause().toBuilder()));
    }
    if (failure.hasEncodedAttributes()) {
      EncodedAttributes encodedAttributes =
          fromPayload(
              failure.getEncodedAttributes(), EncodedAttributes.class, EncodedAttributes.class);
      failure
          .setStackTrace(encodedAttributes.getStackTrace())
          .setMessage(encodedAttributes.getMessage())
          .clearEncodedAttributes();
    }
    switch (failure.getFailureInfoCase()) {
      case APPLICATION_FAILURE_INFO:
        {
          ApplicationFailureInfo.Builder info = failure.getApplicationFailureInfo().toBuilder();
          if (info.hasDetails()) {
            info.setDetails(decodePayloads(info.getDetails()));
          }
          failure.setApplicationFailureInfo(info);
        }
        break;
      case TIMEOUT_FAILURE_INFO:
        {
          TimeoutFailureInfo.Builder info = failure.getTimeoutFailureInfo().toBuilder();
          if (info.hasLastHeartbeatDetails()) {
            info.setLastHeartbeatDetails(decodePayloads(info.getLastHeartbeatDetails()));
          }
          failure.setTimeoutFailureInfo(info);
        }
        break;
      case CANCELED_FAILURE_INFO:
        {
          CanceledFailureInfo.Builder info = failure.getCanceledFailureInfo().toBuilder();
          if (info.hasDetails()) {
            info.setDetails(decodePayloads(info.getDetails()));
          }
          failure.setCanceledFailureInfo(info);
        }
        break;
      case RESET_WORKFLOW_FAILURE_INFO:
        {
          ResetWorkflowFailureInfo.Builder info = failure.getResetWorkflowFailureInfo().toBuilder();
          if (info.hasLastHeartbeatDetails()) {
            info.setLastHeartbeatDetails(decodePayloads(info.getLastHeartbeatDetails()));
          }
          failure.setResetWorkflowFailureInfo(info);
        }
        break;
      default:
        {
          // Other type of failure info don't have anything to decode
        }
    }
    return failure;
  }

  private Payloads encodePayloads(Payloads decodedPayloads) {
    return Payloads.newBuilder().addAllPayloads(encode(decodedPayloads.getPayloadsList())).build();
  }

  private Payloads decodePayloads(Payloads encodedPayloads) {
    return Payloads.newBuilder().addAllPayloads(decode(encodedPayloads.getPayloadsList())).build();
  }

  static class EncodedAttributes {
    private String message;

    private String stackTrace;

    public String getMessage() {
      return message;
    }

    public void setMessage(String message) {
      this.message = message;
    }

    @JsonProperty("stack_trace")
    public String getStackTrace() {
      return stackTrace;
    }

    @JsonProperty("stack_trace")
    public void setStackTrace(String stackTrace) {
      this.stackTrace = stackTrace;
    }
  }
}
