package io.temporal.aws.secretsmanager;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.SecretsManagerException;

public class FetcherTest {
  private HttpServer server;
  private int port;
  private final List<String> requests = new ArrayList<>();
  private volatile Function<String, String[]> respond = id -> new String[] {"404", "not found"};

  @Before
  public void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String query = exchange.getRequestURI().getRawQuery();
          synchronized (requests) {
            requests.add(
                exchange.getRequestURI().getRawPath()
                    + "?"
                    + query
                    + " token="
                    + exchange.getRequestHeaders().getFirst("X-Aws-Parameters-Secrets-Token"));
          }
          String id = java.net.URLDecoder.decode(query.substring("secretId=".length()), "UTF-8");
          String[] resp = respond.apply(id);
          byte[] body = resp[1].getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(Integer.parseInt(resp[0]), body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
    port = server.getAddress().getPort();
  }

  @After
  public void tearDown() {
    server.stop(0);
  }

  private LambdaExtensionFetcher extensionFetcher() {
    return new LambdaExtensionFetcher(
        String.valueOf(port), Collections.singletonMap("AWS_SESSION_TOKEN", "token"));
  }

  @Test
  public void lambdaExtensionFetcher() throws Exception {
    respond =
        id ->
            id.equals("binary/secret")
                ? new String[] {
                  "200",
                  "{\"SecretBinary\": \""
                      + Base64.getEncoder().encodeToString(new byte[] {1, 2, 3})
                      + "\", \"VersionId\": \"v2\"}"
                }
                : new String[] {"200", "{\"SecretString\": \"value\", \"VersionId\": \"v1\"}"};
    LambdaExtensionFetcher fetcher = extensionFetcher();
    assertEquals("extension", fetcher.path());
    Fetcher.SecretValue string = fetcher.fetch("string");
    assertEquals("value", new String(string.value, StandardCharsets.UTF_8));
    assertEquals("v1", string.versionId);
    Fetcher.SecretValue binary = fetcher.fetch("binary/secret");
    assertArrayEquals(new byte[] {1, 2, 3}, binary.value);
    assertEquals("v2", binary.versionId);
    assertEquals(
        new ArrayList<>(
            java.util.Arrays.asList(
                "/secretsmanager/get?secretId=string token=token",
                "/secretsmanager/get?secretId=binary%2Fsecret token=token")),
        requests);
  }

  @Test
  public void lambdaExtensionFetcherErrors() {
    Object[][] cases = {
      {"400", "bad", "client_error"},
      {"500", "oops", "server_error"},
      {"200", "not json", "invalid_response"},
      {"200", "\"string\"", "invalid_response"},
      {"200", "{\"SecretString\": 1}", "invalid_response"},
      {"200", "{\"VersionId\": \"v1\"}", "invalid_response"},
    };
    for (Object[] c : cases) {
      respond = id -> new String[] {(String) c[0], (String) c[1]};
      Exception e = assertThrows(Exception.class, () -> extensionFetcher().fetch("id"));
      assertEquals(c[1] + ": " + e, c[2], Telemetry.classifyFailure(e));
    }
  }

  @Test
  public void lambdaExtensionFetcherConnectionRefused() throws Exception {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      closedPort = socket.getLocalPort();
    }
    Exception e =
        assertThrows(
            Exception.class,
            () ->
                new LambdaExtensionFetcher(String.valueOf(closedPort), Collections.emptyMap())
                    .fetch("id"));
    assertEquals("other", Telemetry.classifyFailure(e));
  }

  @Test
  public void sdkFetcher() throws Exception {
    SecretsManagerClient client = mock(SecretsManagerClient.class);
    List<GetSecretValueRequest> reqs = new ArrayList<>();
    when(client.getSecretValue(any(GetSecretValueRequest.class)))
        .thenAnswer(
            inv -> {
              GetSecretValueRequest req = inv.getArgument(0);
              reqs.add(req);
              return req.secretId().equals("binary")
                  ? GetSecretValueResponse.builder()
                      .secretBinary(SdkBytes.fromByteArray(new byte[] {1, 2}))
                      .versionId("v2")
                      .build()
                  : GetSecretValueResponse.builder().secretString("value").versionId("v1").build();
            });
    Fetcher fetcher = SdkFetcher.create(client);
    assertEquals("sdk", fetcher.path());
    assertEquals("value", new String(fetcher.fetch("string").value, StandardCharsets.UTF_8));
    assertArrayEquals(new byte[] {1, 2}, fetcher.fetch("binary").value);
    // Each request is bounded by the fetch timeout
    assertTrue(reqs.get(0).overrideConfiguration().get().apiCallTimeout().isPresent());
  }

  @Test
  public void sdkFetcherErrors() {
    SecretsManagerClient client = mock(SecretsManagerClient.class);
    when(client.getSecretValue(any(GetSecretValueRequest.class)))
        .thenThrow(
            SecretsManagerException.builder()
                .message("not found")
                .awsErrorDetails(
                    AwsErrorDetails.builder().errorCode("ResourceNotFoundException").build())
                .build())
        .thenThrow(ApiCallTimeoutException.create(10_000))
        .thenReturn(GetSecretValueResponse.builder().versionId("v1").build());
    Fetcher fetcher = SdkFetcher.create(client);
    assertEquals(
        "resource_not_found",
        Telemetry.classifyFailure(assertThrows(Exception.class, () -> fetcher.fetch("id"))));
    assertEquals(
        "timeout",
        Telemetry.classifyFailure(assertThrows(Exception.class, () -> fetcher.fetch("id"))));
    Exception e = assertThrows(Exception.class, () -> fetcher.fetch("id"));
    assertEquals("invalid_response", Telemetry.classifyFailure(e));
    assertEquals("secret has no value", e.getMessage());
  }

  @Test
  public void classifyAwsErrors() {
    Map<String, String> cases = new HashMap<>();
    cases.put("ResourceNotFoundException", "resource_not_found");
    cases.put("AccessDeniedException", "access_denied");
    cases.put("DecryptionFailure", "decryption_failure");
    cases.put("InvalidRequestException", "invalid_request");
    cases.put("InvalidParameterException", "invalid_request");
    cases.put("ThrottlingException", "throttling");
    cases.put("InternalServiceError", "internal_service_error");
    cases.put("SomethingElse", "other");
    for (Map.Entry<String, String> c : cases.entrySet()) {
      assertEquals(
          c.getKey(),
          c.getValue(),
          Telemetry.classifyFailure(
              new FetchErrors.AwsErrorException(c.getKey(), new Exception())));
    }
    assertEquals(
        "other",
        Telemetry.classifyFailure(new FetchErrors.AwsErrorException(null, new Exception())));
  }

  @Test
  public void classifyOtherErrors() {
    assertEquals(
        "invalid_response",
        Telemetry.classifyFailure(new FetchErrors.InvalidResponseException("bad")));
    assertEquals(
        "timeout", Telemetry.classifyFailure(new FetchErrors.FetchTimeoutException("slow", null)));
    assertEquals(
        "client_error",
        Telemetry.classifyFailure(new FetchErrors.LambdaExtensionStatusException(403, "")));
    assertEquals(
        "server_error",
        Telemetry.classifyFailure(new FetchErrors.LambdaExtensionStatusException(503, "")));
    assertEquals(
        "other",
        Telemetry.classifyFailure(new FetchErrors.LambdaExtensionStatusException(302, "")));
    assertEquals("other", Telemetry.classifyFailure(new RuntimeException()));
  }

  @Test
  public void defaultFetcherSelection() {
    Map<String, String> env = new HashMap<>();
    env.put(Fetcher.ENV_LAMBDA_EXTENSION_PORT, "1234");
    assertTrue(Fetcher.newDefaultFetcher(env) instanceof LambdaExtensionFetcher);

    // Empty disables detection, even in Lambda
    env.put(Fetcher.ENV_LAMBDA_EXTENSION_PORT, "");
    env.put(Fetcher.ENV_LAMBDA_FUNCTION_NAME, "fn");
    assertTrue(Fetcher.newDefaultFetcher(env) instanceof SdkFetcher);

    // Not probed outside Lambda
    assertTrue(Fetcher.newDefaultFetcher(new HashMap<>()) instanceof SdkFetcher);
  }

  @Test
  public void probe() throws Exception {
    assertTrue(Fetcher.probe(InetAddress.getLoopbackAddress().getHostAddress(), port));
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      closedPort = socket.getLocalPort();
    }
    assertFalse(Fetcher.probe(InetAddress.getLoopbackAddress().getHostAddress(), closedPort));
  }
}
