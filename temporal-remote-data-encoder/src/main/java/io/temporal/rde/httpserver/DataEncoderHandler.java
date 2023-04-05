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

package io.temporal.rde.httpserver;

import com.google.common.net.HttpHeaders;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.payload.codec.AbstractRemoteDataEncoderCodec;
import io.temporal.payload.codec.ChainCodec;
import io.temporal.payload.codec.PayloadCodec;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.List;
import javax.servlet.http.HttpServletResponse;

class DataEncoderHandler implements HttpHandler {
  public static final String ACCESS_CONTROL_ALLOW_HEADERS = "Access-Control-Allow-Headers";
  public static final String ACCESS_CONTROL_ALLOW_METHODS = "Access-Control-Allow-Methods";
  public static final String ACCESS_CONTROL_ALLOW_ORIGIN = "Access-Control-Allow-Origin";

  private final PayloadCodec codec;

  public DataEncoderHandler(List<PayloadCodec> codecs) {
    this.codec = new ChainCodec(codecs);
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    switch (exchange.getRequestMethod()) {
      case "POST":
        handlePost(exchange);
        break;
      case "OPTIONS":
        handleOptions(exchange);
        break;
      default:
        exchange.sendResponseHeaders(HttpServletResponse.SC_METHOD_NOT_ALLOWED, -1);
    }
    // not in try-catch to let the server form an error response if exception occurs
    exchange.close();
  }

  private void handleOptions(HttpExchange exchange) throws IOException {
    exchange.getResponseHeaders().add(ACCESS_CONTROL_ALLOW_METHODS, "POST, OPTIONS");
    exchange.getResponseHeaders().add(ACCESS_CONTROL_ALLOW_ORIGIN, "*");
    exchange.getResponseHeaders().add(ACCESS_CONTROL_ALLOW_HEADERS, "*");
    exchange.sendResponseHeaders(204, -1);
  }

  private void handlePost(HttpExchange exchange) throws IOException {
    String contentType = exchange.getRequestHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
    if (contentType == null
        || !contentType.startsWith(AbstractRemoteDataEncoderCodec.CONTENT_TYPE_APPLICATION_JSON)) {
      exchange.sendResponseHeaders(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, -1);
      return;
    }

    String path = exchange.getRequestURI().getPath();
    boolean encode;
    if (path.endsWith(AbstractRemoteDataEncoderCodec.ENCODE_PATH_POSTFIX)) {
      encode = true;
    } else if (path.endsWith(AbstractRemoteDataEncoderCodec.DECODE_PATH_POSTFIX)) {
      encode = false;
    } else {
      exchange.sendResponseHeaders(HttpServletResponse.SC_NOT_FOUND, -1);
      return;
    }

    Payloads.Builder incomingPayloads = Payloads.newBuilder();
    try (InputStreamReader ioReader = new InputStreamReader(exchange.getRequestBody())) {
      AbstractRemoteDataEncoderCodec.JSON_FORMAT.merge(ioReader, incomingPayloads);
    } catch (IOException e) {
      exchange.sendResponseHeaders(HttpServletResponse.SC_BAD_REQUEST, -1);
      return;
    }

    List<Payload> incomingPayloadsList = incomingPayloads.build().getPayloadsList();
    List<Payload> outgoingPayloadsList =
        encode ? codec.encode(incomingPayloadsList) : codec.decode(incomingPayloadsList);

    exchange
        .getResponseHeaders()
        .add(
            HttpHeaders.CONTENT_TYPE, AbstractRemoteDataEncoderCodec.CONTENT_TYPE_APPLICATION_JSON);
    exchange.getResponseHeaders().add(ACCESS_CONTROL_ALLOW_ORIGIN, "*");
    exchange.sendResponseHeaders(HttpServletResponse.SC_OK, 0);

    try (OutputStreamWriter out = new OutputStreamWriter(exchange.getResponseBody())) {
      AbstractRemoteDataEncoderCodec.JSON_PRINTER.appendTo(
          Payloads.newBuilder().addAllPayloads(outgoingPayloadsList).build(), out);
    }
  }
}
