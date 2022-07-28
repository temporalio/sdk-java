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

package io.temporal.rde.servlet;

import com.google.common.net.HttpHeaders;
import com.google.protobuf.InvalidProtocolBufferException;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.payload.codec.AbstractRemoteDataEncoderCodec;
import io.temporal.payload.codec.ChainCodec;
import io.temporal.payload.codec.PayloadCodec;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.List;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Provides a Servlet implementation of Remote Data Encoder contract that can be used in any Servlet
 * Container supporting Servlet 3.x or 4.x specifications. This implementation is compliant with
 * {@link AbstractRemoteDataEncoderCodec} and its subclasses supplied by Temporal JavaSDK.
 *
 * <p>If you look for Jakarta Servlet implementation (Servlet 5.x and higher), Temporal SDL doesn't
 * provide it out-of-the-box. <br>
 * You can easily port this class to Servlet 5.x specification yourself by replacing {@code import
 * javax.servlet.*} with {@code import jakarta.servlet.*}. No other changes needed.
 */
public class RDEServlet4 extends HttpServlet {
  private final PayloadCodec codec;

  public RDEServlet4(List<PayloadCodec> codecs) {
    this.codec = new ChainCodec(codecs);
  }

  @Override
  protected void doPost(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    if (!request.getMethod().equals("POST")) {
      response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED, "Only POST is allowed");
      return;
    }

    String contentType = request.getContentType();
    if (contentType == null
        || !contentType.startsWith(AbstractRemoteDataEncoderCodec.CONTENT_TYPE_APPLICATION_JSON)) {
      response.sendError(
          HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE,
          "Unsupported content type, application/json is expected");
      return;
    }

    String path = request.getRequestURI();
    boolean encode;
    if (path.endsWith(AbstractRemoteDataEncoderCodec.ENCODE_PATH_POSTFIX)) {
      encode = true;
    } else if (path.endsWith(AbstractRemoteDataEncoderCodec.DECODE_PATH_POSTFIX)) {
      encode = false;
    } else {
      response.sendError(
          HttpServletResponse.SC_NOT_FOUND, "Path should end with /encode or /decode");
      return;
    }

    Payloads.Builder incomingPayloads = Payloads.newBuilder();
    ServletInputStream inputStream = request.getInputStream();
    try (InputStreamReader ioReader = new InputStreamReader(inputStream)) {
      AbstractRemoteDataEncoderCodec.JSON_FORMAT.merge(ioReader, incomingPayloads);
    } catch (InvalidProtocolBufferException e) {
      response.sendError(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
      return;
    }

    List<Payload> incomingPayloadsList = incomingPayloads.build().getPayloadsList();
    List<Payload> outgoingPayloadsList =
        encode ? codec.encode(incomingPayloadsList) : codec.decode(incomingPayloadsList);

    response.addHeader(
        HttpHeaders.CONTENT_TYPE, AbstractRemoteDataEncoderCodec.CONTENT_TYPE_APPLICATION_JSON);
    response.setStatus(HttpServletResponse.SC_OK);
    ServletOutputStream outputStream = response.getOutputStream();
    try (OutputStreamWriter out = new OutputStreamWriter(outputStream)) {
      AbstractRemoteDataEncoderCodec.JSON_PRINTER.appendTo(
          Payloads.newBuilder().addAllPayloads(outgoingPayloadsList).build(), out);
    }
  }
}
