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
