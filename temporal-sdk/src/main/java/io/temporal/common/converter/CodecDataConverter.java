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
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.failure.v1.ApplicationFailureInfo;
import io.temporal.api.failure.v1.CanceledFailureInfo;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.failure.v1.Failure.Builder;
import io.temporal.api.failure.v1.ResetWorkflowFailureInfo;
import io.temporal.api.failure.v1.TimeoutFailureInfo;
import io.temporal.payload.codec.ChainCodec;
import io.temporal.payload.codec.PayloadCodec;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
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
  private final DataConverter dataConverter;
  private final PayloadCodec codec;

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
    this.dataConverter = dataConverter;
    this.codec = new ChainCodec(codecs);
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
      content = Optional.of(decode(content.get()));
    }
    return dataConverter.fromPayloads(index, content, valueType, valueGenericType);
  }

  @Override
  public Failure exceptionToFailure(Throwable e) {
    return this.encodeFailure(dataConverter.exceptionToFailure(e));
  }

  private Failure encodeFailure(Failure decodedFailure) {
    Builder encodedFailure = decodedFailure.toBuilder();
    if (decodedFailure.hasCause()) {
      encodedFailure.setCause(encodeFailure(decodedFailure.getCause()));
    }
    if (decodedFailure.hasEncodedAttributes()) {
      encodedFailure.setEncodedAttributes(encode(decodedFailure.getEncodedAttributes()));
    }
    switch (decodedFailure.getFailureInfoCase()) {
      case APPLICATION_FAILURE_INFO:
        {
          ApplicationFailureInfo decodedInfo = decodedFailure.getApplicationFailureInfo();
          ApplicationFailureInfo.Builder encodedInfo = decodedInfo.toBuilder();
          if (decodedInfo.hasDetails()) {
            encodedInfo.setDetails(encode(decodedInfo.getDetails()));
          }
          encodedFailure.setApplicationFailureInfo(encodedInfo);
        }
        break;
      case TIMEOUT_FAILURE_INFO:
        {
          TimeoutFailureInfo decodedInfo = decodedFailure.getTimeoutFailureInfo();
          TimeoutFailureInfo.Builder encodedInfo = decodedInfo.toBuilder();
          if (decodedInfo.hasLastHeartbeatDetails()) {
            encodedInfo.setLastHeartbeatDetails(encode(decodedInfo.getLastHeartbeatDetails()));
          }
          encodedFailure.setTimeoutFailureInfo(encodedInfo);
        }
        break;
      case CANCELED_FAILURE_INFO:
        {
          CanceledFailureInfo decodedInfo = decodedFailure.getCanceledFailureInfo();
          CanceledFailureInfo.Builder encodedInfo = decodedInfo.toBuilder();
          if (decodedInfo.hasDetails()) {
            encodedInfo.setDetails(encode(decodedInfo.getDetails()));
          }
          encodedFailure.setCanceledFailureInfo(encodedInfo);
        }
        break;
      case RESET_WORKFLOW_FAILURE_INFO:
        {
          ResetWorkflowFailureInfo decodedInfo = decodedFailure.getResetWorkflowFailureInfo();
          ResetWorkflowFailureInfo.Builder encodedInfo = decodedInfo.toBuilder();
          if (decodedInfo.hasLastHeartbeatDetails()) {
            encodedInfo.setLastHeartbeatDetails(encode(decodedInfo.getLastHeartbeatDetails()));
          }
          encodedFailure.setResetWorkflowFailureInfo(encodedInfo);
        }
        break;
      default:
        {
          // Other type of failure info don't have anything to encode
        }
    }
    return encodedFailure.build();
  }

  @Override
  public RuntimeException failureToException(Failure failure) {
    return dataConverter.failureToException(this.decodeFailure(failure));
  }

  private Failure decodeFailure(Failure encodedFailure) {
    Builder decodedFailure = encodedFailure.toBuilder();
    if (encodedFailure.hasCause()) {
      decodedFailure.setCause(decodeFailure(encodedFailure.getCause()));
    }
    if (encodedFailure.hasEncodedAttributes()) {
      decodedFailure.setEncodedAttributes(decode(encodedFailure.getEncodedAttributes()));
    }
    switch (encodedFailure.getFailureInfoCase()) {
      case APPLICATION_FAILURE_INFO:
        {
          ApplicationFailureInfo encodedInfo = encodedFailure.getApplicationFailureInfo();
          ApplicationFailureInfo.Builder decodedInfo = encodedInfo.toBuilder();
          if (encodedInfo.hasDetails()) {
            decodedInfo.setDetails(decode(encodedInfo.getDetails()));
          }
          decodedFailure.setApplicationFailureInfo(decodedInfo);
        }
        break;
      case TIMEOUT_FAILURE_INFO:
        {
          TimeoutFailureInfo encodedInfo = encodedFailure.getTimeoutFailureInfo();
          TimeoutFailureInfo.Builder decodedInfo = encodedInfo.toBuilder();
          if (encodedInfo.hasLastHeartbeatDetails()) {
            decodedInfo.setLastHeartbeatDetails(decode(encodedInfo.getLastHeartbeatDetails()));
          }
          decodedFailure.setTimeoutFailureInfo(decodedInfo);
        }
        break;
      case CANCELED_FAILURE_INFO:
        {
          CanceledFailureInfo encodedInfo = encodedFailure.getCanceledFailureInfo();
          CanceledFailureInfo.Builder decodedInfo = encodedInfo.toBuilder();
          if (encodedInfo.hasDetails()) {
            decodedInfo.setDetails(decode(encodedInfo.getDetails()));
          }
          decodedFailure.setCanceledFailureInfo(decodedInfo);
        }
        break;
      case RESET_WORKFLOW_FAILURE_INFO:
        {
          ResetWorkflowFailureInfo encodedInfo = encodedFailure.getResetWorkflowFailureInfo();
          ResetWorkflowFailureInfo.Builder decodedInfo = encodedInfo.toBuilder();
          if (encodedInfo.hasLastHeartbeatDetails()) {
            decodedInfo.setLastHeartbeatDetails(decode(encodedInfo.getLastHeartbeatDetails()));
          }
          decodedFailure.setResetWorkflowFailureInfo(decodedInfo);
        }
        break;
      default:
        {
          // Other type of failure info don't have anything to decode
        }
    }
    return decodedFailure.build();
  }

  @Nonnull
  @Override
  public List<Payload> encode(@Nonnull List<Payload> payloads) {
    return codec.encode(payloads);
  }

  private Payload encode(Payload payload) {
    return codec.encode(Collections.singletonList(payload)).get(0);
  }

  private Payloads encode(Payloads decodedPayloads) {
    List<Payload> encodedPayloads = codec.encode(decodedPayloads.getPayloadsList());
    return Payloads.newBuilder().addAllPayloads(encodedPayloads).build();
  }

  @Nonnull
  @Override
  public List<Payload> decode(@Nonnull List<Payload> payloads) {
    return codec.decode(payloads);
  }

  private Payload decode(Payload payload) {
    return codec.decode(Collections.singletonList(payload)).get(0);
  }

  private Payloads decode(Payloads encodedPayloads) {
    List<Payload> decodedPayloads = codec.decode(encodedPayloads.getPayloadsList());
    return Payloads.newBuilder().addAllPayloads(decodedPayloads).build();
  }
}
