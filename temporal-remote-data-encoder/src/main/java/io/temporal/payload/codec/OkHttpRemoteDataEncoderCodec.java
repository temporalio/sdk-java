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

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import okhttp3.*;

public class OkHttpRemoteDataEncoderCodec extends AbstractRemoteDataEncoderCodec {
  private final OkHttpClient client;

  public OkHttpRemoteDataEncoderCodec(OkHttpClient client, String rdeUrl) {
    super(rdeUrl);
    this.client = client;
  }

  @Override
  protected Reader performPost(String url, String json) throws IOException {
    RequestBody body =
        RequestBody.create(
            json, MediaType.parse(AbstractRemoteDataEncoderCodec.CONTENT_TYPE_APPLICATION_JSON));

    Request request = new Request.Builder().url(url).post(body).build();

    Call call = client.newCall(request);
    Response response = call.execute();
    if (response.code() == 200) {
      if (response.body() != null) {
        return new InputStreamReader(response.body().byteStream());
      } else {
        throw new IOException("Remote Data Encoder response body is empty. Response: " + response);
      }
    } else {
      throw new IOException(
          "Remote Data Encoder response status code is not 200 OK. Response: " + response);
    }
  }
}
