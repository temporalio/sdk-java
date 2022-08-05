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

import com.google.common.base.Preconditions;
import com.sun.net.httpserver.HttpServer;
import io.temporal.payload.codec.AbstractRemoteDataEncoderCodec;
import io.temporal.payload.codec.PayloadCodec;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;

/**
 * Provides a simple standalone http server implementation of Remote Data Encoder contract. This
 * implementation is compliant with {@link AbstractRemoteDataEncoderCodec} and its subclasses
 * supplied by Temporal JavaSDK.
 */
public class RDEHttpServer implements Closeable {
  private final List<PayloadCodec> codecs;
  private final int port;

  private HttpServer server;

  public RDEHttpServer(List<PayloadCodec> codecs) {
    this(codecs, -1);
  }

  public RDEHttpServer(List<PayloadCodec> codecs, int port) {
    this.codecs = codecs;
    this.port = port;
  }

  public synchronized void start() throws IOException {
    Preconditions.checkState(server == null, "Server already started");
    server =
        this.port > 0 ? HttpServer.create(new InetSocketAddress(port), 0) : HttpServer.create();

    server.createContext(
        AbstractRemoteDataEncoderCodec.ENCODE_PATH_POSTFIX, new DataEncoderHandler(codecs));
    server.createContext(
        AbstractRemoteDataEncoderCodec.DECODE_PATH_POSTFIX, new DataEncoderHandler(codecs));

    server.setExecutor(null);
    server.start();
  }

  public int getPort() {
    return server != null ? server.getAddress().getPort() : port;
  }

  @Override
  public synchronized void close() {
    if (server != null) {
      server.stop(0);
    }
  }
}
