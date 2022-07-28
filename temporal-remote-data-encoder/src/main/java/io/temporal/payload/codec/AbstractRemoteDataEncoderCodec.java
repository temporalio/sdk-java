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

import com.google.protobuf.util.JsonFormat;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import java.io.IOException;
import java.io.Reader;
import java.util.List;
import javax.annotation.Nonnull;

/**
 * Performs encoding/decoding of the payloads via the Remote Data Encoder (RDE) available over http.
 *
 * <p>
 *
 * <h2>Remote Data Encoder Http Server specification</h2>
 *
 * <p>RDE Server must:
 *
 * <ul>
 *   <li>Respond on URL paths ending with "/encode" and "/decode" for encoding and decoding
 *       functionality respectively
 *   <li>Accept POST requests with "application/json" content type and send back response with
 *       "application/json" content type
 *   <li>Expect and emit {@link Payloads} serialized to json format using <a link =
 *       "https://developers.google.com/protocol-buffers/docs/proto3#json">Proto3 Json Mapping</a>
 *       and sent in Http Request / Response body
 * </ul>
 *
 * <p>RDE Server may:
 *
 * <ul>
 *   <li>On encoding accept <i>M</i> {@link Payload}s inside incoming {@link Payloads} and return
 *       back {@link Payloads} with <i>N</i> {@link Payload}s and have <i>M</i> &lt;&gt; <i>N</i>.
 *       So, encoded payloads don't have to correspond 1-1 to the incoming payloads. This enables
 *       better compaction if required. If this is the case, a decoding path should convert <i>N</i>
 *       payloads back to <i>M</i>
 * </ul>
 */
public abstract class AbstractRemoteDataEncoderCodec implements PayloadCodec {
  public static final String ENCODE_PATH_POSTFIX = "/encode";
  public static final String DECODE_PATH_POSTFIX = "/decode";
  public static final String CONTENT_TYPE_APPLICATION_JSON = "application/json";

  public static final JsonFormat.Parser JSON_FORMAT = JsonFormat.parser();
  public static final JsonFormat.Printer JSON_PRINTER = JsonFormat.printer();

  private final String rdeEncodeUrl;
  private final String rdeDecodeUrl;

  public AbstractRemoteDataEncoderCodec(String rdeUrl) {
    this.rdeEncodeUrl = rdeUrl + ENCODE_PATH_POSTFIX;
    this.rdeDecodeUrl = rdeUrl + DECODE_PATH_POSTFIX;
  }

  @Nonnull
  @Override
  public List<Payload> encode(@Nonnull List<Payload> payloads) {
    return transform(payloads, rdeEncodeUrl);
  }

  @Nonnull
  @Override
  public List<Payload> decode(@Nonnull List<Payload> payloads) {
    return transform(payloads, rdeDecodeUrl);
  }

  protected List<Payload> transform(@Nonnull List<Payload> payloads, String url) {
    Payloads outgoingPayloads = Payloads.newBuilder().addAllPayloads(payloads).build();
    try {
      String json = JSON_PRINTER.print(outgoingPayloads);
      try (Reader reader = performPost(url, json)) {
        Payloads.Builder incomingPayloads = Payloads.newBuilder();
        JSON_FORMAT.merge(reader, incomingPayloads);
        return incomingPayloads.getPayloadsList();
      }
    } catch (IOException e) {
      throw new PayloadCodecException(e);
    }
  }

  /**
   * An implementation should perform a blocking HTTP POST request to the {@code url} with
   * "Content-Type: application/json" header and supplied {@code json} as the body. The
   * implementation is also responsible for enforcing a request timeout.
   *
   * @param url encoding or decoding URL to call
   * @param json JSON representation of the {@link Payloads} to be encoded or decoded
   * @return a {@link Reader} to read the response body from, this Reader will be closed by {@code
   *     AbstractRemoteDataEncoderCodec}
   * @throws IOException implementations should throw IOException if an error occurs during the HTTP
   *     request or response is not valid. A valid response of Remote Data Encoder should have 200
   *     Status code, "Content-Type: application/json" header and a body
   */
  protected abstract Reader performPost(String url, String json) throws IOException;
}
