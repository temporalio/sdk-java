package io.temporal.aws.secretsmanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Fetches secrets through the AWS Parameters and Secrets Lambda Extension. See
 * https://docs.aws.amazon.com/secretsmanager/latest/userguide/retrieving-secrets_lambda.html.
 */
final class LambdaExtensionFetcher implements Fetcher {
  private static final String ENV_AWS_SESSION_TOKEN = "AWS_SESSION_TOKEN";
  private static final String TOKEN_HEADER = "X-Aws-Parameters-Secrets-Token";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String endpoint;
  private final Map<String, String> processEnv;

  LambdaExtensionFetcher(String port, Map<String, String> processEnv) {
    this.endpoint =
        "http://" + LAMBDA_EXTENSION_HOST + ":" + port + "/secretsmanager/get?secretId=";
    this.processEnv = processEnv;
  }

  @Override
  public String path() {
    return Telemetry.FETCH_PATH_EXTENSION;
  }

  @Override
  public SecretValue fetch(String secretId) throws Exception {
    URL url = new URL(endpoint + urlEncode(secretId));
    // No proxy, so that proxy settings never apply to requests to the local extension
    HttpURLConnection conn = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
    conn.setConnectTimeout((int) FETCH_TIMEOUT_MILLIS);
    conn.setReadTimeout((int) FETCH_TIMEOUT_MILLIS);
    String token = processEnv.get(ENV_AWS_SESSION_TOKEN);
    conn.setRequestProperty(TOKEN_HEADER, token != null ? token : "");
    int status;
    String body;
    try {
      status = conn.getResponseCode();
      body = readAll(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
    } catch (SocketTimeoutException e) {
      throw new FetchErrors.FetchTimeoutException(
          "AWS Secrets Manager secret fetch timed out after " + FETCH_TIMEOUT_MILLIS + "ms", e);
    }
    if (status != 200) {
      throw new FetchErrors.LambdaExtensionStatusException(status, body);
    }
    return parseResponse(body);
  }

  static SecretValue parseResponse(String body) throws FetchErrors.InvalidResponseException {
    JsonNode out;
    try {
      // Same shape as the GetSecretValue response, with SecretBinary base64
      out = MAPPER.readTree(body);
    } catch (IOException e) {
      throw invalid(e.getMessage(), e);
    }
    if (out == null || !out.isObject()) {
      throw invalid("not an object", null);
    }
    String secretString = textField(out, "SecretString");
    String secretBinary = textField(out, "SecretBinary");
    String versionId = textField(out, "VersionId");
    byte[] binary = null;
    if (secretBinary != null && !secretBinary.isEmpty()) {
      try {
        binary = Base64.getDecoder().decode(secretBinary);
      } catch (IllegalArgumentException e) {
        throw invalid("invalid SecretBinary: " + e.getMessage(), e);
      }
    }
    return SecretValue.fromResponse(secretString, binary, versionId);
  }

  private static String textField(JsonNode out, String name)
      throws FetchErrors.InvalidResponseException {
    JsonNode field = out.get(name);
    if (field == null || field.isNull()) {
      return null;
    }
    if (!field.isTextual()) {
      throw invalid(name + " must be a string", null);
    }
    return field.asText();
  }

  private static FetchErrors.InvalidResponseException invalid(String message, Throwable cause) {
    return new FetchErrors.InvalidResponseException(
        "failed decoding AWS Parameters and Secrets Lambda Extension response: " + message, cause);
  }

  private static String urlEncode(String s) {
    try {
      return URLEncoder.encode(s, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String readAll(InputStream in) throws IOException {
    if (in == null) {
      return "";
    }
    try (InputStream stream = in) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      int n;
      while ((n = stream.read(buf)) != -1) {
        out.write(buf, 0, n);
      }
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
  }
}
