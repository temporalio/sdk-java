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

import com.google.common.base.Preconditions;
import com.google.common.reflect.TypeToken;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.failure.v1.ApplicationFailureInfo;
import io.temporal.api.failure.v1.CanceledFailureInfo;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.failure.v1.ResetWorkflowFailureInfo;
import io.temporal.api.failure.v1.TimeoutFailureInfo;
import io.temporal.payload.codec.ChainCodec;
import io.temporal.payload.codec.PayloadCodec;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nonnull;

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
  private static final String STACK_TRACE_KEY = "stack_trace";
  private static final String MESSAGE_KEY = "message";

  private static final Type HASH_MAP_STRING_STRING_TYPE =
      new TypeToken<HashMap<String, String>>() {}.getType();

  private final DataConverter dataConverter;
  private final PayloadCodec codec;
  private final boolean encodeDefaultAttributes;

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
   * @param dataConverter to delegate data conversion to
   * @param codecs to delegate bytes encoding/decoding to. When encoding, the codecs are applied
   *     last to first meaning the earlier encoders wrap the later ones. When decoding, the decoders
   *     are applied first to last to reverse the effect
   */
  public CodecDataConverter(
      DataConverter dataConverter,
      Collection<PayloadCodec> codecs,
      boolean encodeDefaultAttributes) {
    this.dataConverter = dataConverter;
    this.codec = new ChainCodec(codecs);
    this.encodeDefaultAttributes = encodeDefaultAttributes;
  }

  @Override
  public <T> Optional<Payload> toPayload(T value) {
    Optional<Payload> payload = dataConverter.toPayload(value);
    List<Payload> encodedPayloads = codec.encode(Collections.singletonList(payload.get()));
    Preconditions.checkState(encodedPayloads.size() == 1, "Expected one encoded payload");
    return Optional.of(encodedPayloads.get(0));
  }

  @Override
  public <T> T fromPayload(Payload payload, Class<T> valueClass, Type valueType) {
    List<Payload> decodedPayload = codec.decode(Collections.singletonList(payload));
    Preconditions.checkState(decodedPayload.size() == 1, "Expected one decoded payload");
    return dataConverter.fromPayload(decodedPayload.get(0), valueClass, valueType);
  }

  @Override
  public Optional<Payloads> toPayloads(Object... values) throws DataConverterException {
    Optional<Payloads> payloads = dataConverter.toPayloads(values);
    if (payloads.isPresent()) {
      List<Payload> encodedPayloads = codec.encode(payloads.get().getPayloadsList());
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
    return dataConverter.fromPayloads(index, content, valueType, valueGenericType);
  }

  @Override
  @Nonnull
  public Failure exceptionToFailure(@Nonnull Throwable e) {
    Preconditions.checkNotNull(e, "e");
    return this.encodeFailure(dataConverter.exceptionToFailure(e).toBuilder()).build();
  }

  private Failure.Builder encodeFailure(Failure.Builder failure) {
    if (failure.hasCause()) {
      failure.setCause(encodeFailure(failure.getCause().toBuilder()));
    }
    if (this.encodeDefaultAttributes) {
      Map<String, String> encodedAttributes = new HashMap<>();
      encodedAttributes.put(STACK_TRACE_KEY, failure.getStackTrace());
      encodedAttributes.put(MESSAGE_KEY, failure.getMessage());
      Payload encodedAttributesPayload =
          encodePayload(DefaultDataConverter.STANDARD_INSTANCE.toPayload(encodedAttributes).get());
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

  @Override
  @Nonnull
  public RuntimeException failureToException(@Nonnull Failure failure) {
    Preconditions.checkNotNull(failure, "failure");
    return dataConverter.failureToException(this.decodeFailure(failure.toBuilder()).build());
  }

  private Failure.Builder decodeFailure(Failure.Builder failure) {
    if (failure.hasCause()) {
      failure.setCause(decodeFailure(failure.getCause().toBuilder()));
    }
    if (failure.hasEncodedAttributes()) {
      Payload encodedAttributesPayload = decodePayload(failure.getEncodedAttributes());
      Map<String, String> encodedAttributes =
          DefaultDataConverter.STANDARD_INSTANCE.fromPayload(
              encodedAttributesPayload, HashMap.class, HASH_MAP_STRING_STRING_TYPE);
      failure
          .setStackTrace(encodedAttributes.get(STACK_TRACE_KEY))
          .setMessage(encodedAttributes.get(MESSAGE_KEY))
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

  @Nonnull
  @Override
  public List<Payload> encode(@Nonnull List<Payload> payloads) {
    return codec.encode(payloads);
  }

  private Payload encodePayload(Payload payload) {
    return codec.encode(Collections.singletonList(payload)).get(0);
  }

  private Payloads encodePayloads(Payloads decodedPayloads) {
    List<Payload> encodedPayloads = codec.encode(decodedPayloads.getPayloadsList());
    return Payloads.newBuilder().addAllPayloads(encodedPayloads).build();
  }

  @Nonnull
  @Override
  public List<Payload> decode(@Nonnull List<Payload> payloads) {
    return codec.decode(payloads);
  }

  private Payload decodePayload(Payload payload) {
    return codec.decode(Collections.singletonList(payload)).get(0);
  }

  private Payloads decodePayloads(Payloads encodedPayloads) {
    List<Payload> decodedPayloads = codec.decode(encodedPayloads.getPayloadsList());
    return Payloads.newBuilder().addAllPayloads(decodedPayloads).build();
  }
}
