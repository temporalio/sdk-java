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
 * responsible for a subsequent byte <-> byte manipulation such as encryption or compression
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
      List<Payload> decodedPayloads = codec.decode(content.get().getPayloadsList());
      content = Optional.of(Payloads.newBuilder().addAllPayloads(decodedPayloads).build());
    }
    return dataConverter.fromPayloads(index, content, valueType, valueGenericType);
  }

  @Nonnull
  @Override
  public List<Payload> encode(@Nonnull List<Payload> payloads) {
    return codec.encode(payloads);
  }

  @Nonnull
  @Override
  public List<Payload> decode(@Nonnull List<Payload> payloads) {
    return codec.decode(payloads);
  }
}
