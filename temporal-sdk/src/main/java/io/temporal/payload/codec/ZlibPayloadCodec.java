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

package io.temporal.payload.codec;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.EncodingKeys;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.Deflater;
import java.util.zip.InflaterInputStream;
import javax.annotation.Nonnull;

/**
 * PayloadCodec that provides a basic compression using Zlib.
 *
 * <p>Please note that this is by no means the best solution for lots of small payloads which is
 * typical for a lot of applications. You can use this implementation as an example and base for
 * your own implementation using the compressor of your choice, for example <a
 * href="https://github.com/xerial/snappy-java">Google Snappy</a>
 */
public class ZlibPayloadCodec implements PayloadCodec {
  static final ByteString METADATA_ENCODING_ZLIB = ByteString.copyFromUtf8("binary/zlib");
  final int level;

  public ZlibPayloadCodec() {
    this(Deflater.DEFAULT_COMPRESSION);
  }

  /**
   * @param level compression level
   * @see Deflater#Deflater(int level)
   */
  public ZlibPayloadCodec(int level) {
    this.level = level;
  }

  @Nonnull
  @Override
  public List<Payload> encode(@Nonnull List<Payload> payloads) {
    return payloads.stream().map(this::encodePayload).collect(Collectors.toList());
  }

  @Nonnull
  @Override
  public List<Payload> decode(@Nonnull List<Payload> payloads) {
    return payloads.stream().map(this::decodePayload).collect(Collectors.toList());
  }

  private Payload encodePayload(final Payload originalPayload) {
    if (originalPayload.getSerializedSize() < 7) {
      // Can't be effectively deflated because of the Zlib ANTLER-32 header which takes 4 bytes
      return originalPayload;
    }
    byte[] input = originalPayload.toByteArray();
    int inputSize = input.length;
    Deflater compressor = new Deflater(level);
    compressor.setInput(input);
    compressor.finish();
    byte[] output = new byte[inputSize];
    int deflatedLength = compressor.deflate(output);
    compressor.end();

    if (deflatedLength < inputSize) {
      return Payload.newBuilder()
          .putMetadata(EncodingKeys.METADATA_ENCODING_KEY, METADATA_ENCODING_ZLIB)
          .setData(ByteString.copyFrom(output, 0, deflatedLength))
          .build();
    } else {
      return originalPayload;
    }
  }

  private Payload decodePayload(final Payload originalPayload) {
    if (METADATA_ENCODING_ZLIB.equals(
        originalPayload.getMetadataOrDefault(EncodingKeys.METADATA_ENCODING_KEY, null))) {
      try (InflaterInputStream inflaterInputStream =
          new InflaterInputStream(originalPayload.getData().newInput())) {
        return Payload.parseFrom(inflaterInputStream);
      } catch (IOException e) {
        throw new PayloadCodecException(e);
      }
    } else {
      // This payload is not encoded by this codec
      return originalPayload;
    }
  }
}
