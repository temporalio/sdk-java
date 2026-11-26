package io.temporal.aws.secretsmanager;

import static io.temporal.aws.secretsmanager.TestHelpers.*;
import static org.junit.Assert.*;

import io.temporal.aws.secretsmanager.SecretCache.ClientCertPair;
import io.temporal.aws.secretsmanager.SecretCache.SecretFetchException;
import io.temporal.aws.secretsmanager.SecretCache.SecretIds;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.junit.Test;

public class SecretCacheTest {
  private static final long REFRESH = TimeUnit.MINUTES.toNanos(1);
  private static final long MAX_STALENESS = TimeUnit.HOURS.toNanos(1);
  private static final long RETRY = SecretCache.FAILED_REFRESH_RETRY_INTERVAL_NANOS;

  private final FakeFetcher fetcher = new FakeFetcher();
  private final AtomicLong clock = new AtomicLong(1_000_000_000L);
  private final RecordingTelemetry tel = new RecordingTelemetry();

  private SecretCache newCache(SecretIds ids, long refresh) {
    SecretCache cache = new SecretCache(fetcher, ids, refresh, MAX_STALENESS);
    cache.now = clock::get;
    return cache;
  }

  private SecretCache newApiKeyCache(long refresh) {
    return newCache(new SecretIds("key", "", "", ""), refresh);
  }

  private static Map<String, String> tags(String... kv) {
    Map<String, String> m = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put(kv[i], kv[i + 1]);
    }
    return m;
  }

  private List<String> results() {
    return tel.take(Telemetry.METRIC_CACHE_LOOKUP).stream()
        .map(t -> t.get(Telemetry.LABEL_RESULT))
        .collect(Collectors.toList());
  }

  @Test
  public void hitMissAndRotation() {
    fetcher.set("key", "v1", "ver1");
    SecretCache cache = newApiKeyCache(REFRESH);
    assertEquals("v1", cache.get(cache.apiKey, tel));
    assertEquals("v1", cache.get(cache.apiKey, tel));
    assertEquals(1, fetcher.calls("key"));
    assertEquals(
        Arrays.asList(
            tags("secret_type", "api_key", "fetch_path", "sdk", "result", "miss"),
            tags("secret_type", "api_key", "fetch_path", "sdk", "result", "hit")),
        tel.take(Telemetry.METRIC_CACHE_LOOKUP));
    assertEquals(
        Arrays.asList(tags("secret_type", "api_key", "fetch_path", "sdk", "outcome", "success")),
        tel.take(Telemetry.METRIC_FETCH_LATENCY));

    clock.addAndGet(REFRESH);
    fetcher.set("key", "v2", "ver2");
    assertEquals("v2", cache.get(cache.apiKey, tel));
    assertEquals(2, fetcher.calls("key"));
  }

  @Test
  public void zeroRefreshIntervalAlwaysFetches() {
    fetcher.set("key", "v1", "ver1");
    SecretCache cache = newApiKeyCache(0);
    cache.get(cache.apiKey, tel);
    cache.get(cache.apiKey, tel);
    assertEquals(2, fetcher.calls("key"));
  }

  @Test
  public void failureFallsBackToStaleValueAndWaitsBeforeRetrying() {
    fetcher.set("key", "v1", "ver1");
    SecretCache cache = newApiKeyCache(REFRESH);
    cache.get(cache.apiKey, tel);
    tel.take(Telemetry.METRIC_CACHE_LOOKUP);

    fetcher.fail(
        "key", new FetchErrors.AwsErrorException("AccessDeniedException", new Exception()));
    clock.addAndGet(REFRESH);
    assertEquals("v1", cache.get(cache.apiKey, tel));
    assertEquals(
        Arrays.asList(
            tags(
                "secret_type",
                "api_key",
                "fetch_path",
                "sdk",
                "failure_reason",
                "access_denied",
                "fallback",
                "stale")),
        tel.take(Telemetry.METRIC_FETCH_FAILED));

    // Not retried until the retry interval has passed
    clock.addAndGet(RETRY - 1);
    assertEquals("v1", cache.get(cache.apiKey, tel));
    assertEquals(2, fetcher.calls("key"));
    clock.addAndGet(1);
    assertEquals("v1", cache.get(cache.apiKey, tel));
    assertEquals(3, fetcher.calls("key"));
    assertEquals(
        Arrays.asList("miss_with_fallback", "miss_with_fallback", "miss_with_fallback"), results());

    fetcher.set("key", "v1", "ver1");
    clock.addAndGet(RETRY);
    assertEquals("v1", cache.get(cache.apiKey, tel));
    assertNull(cache.apiKey.failedAt);
  }

  @Test
  public void errorIsRecordedAsFailedFetch() {
    // E.g. from a mismatched AWS SDK version
    fetcher.fail("key", new NoSuchMethodError("mismatched"));
    SecretCache cache = newApiKeyCache(REFRESH);
    SecretFetchException e =
        assertThrows(SecretFetchException.class, () -> cache.get(cache.apiKey, tel));
    assertTrue(e.getCause() instanceof NoSuchMethodError);
    assertEquals("other", e.reason);

    // Not left in flight, so the next lookup fetches again
    assertNull(inFlight(cache));
    fetcher.set("key", "v1", "ver1");
    assertEquals("v1", cache.get(cache.apiKey, tel));
  }

  @Test
  public void failureWithoutPreviousValueThrows() {
    fetcher.fail(
        "key",
        new FetchErrors.AwsErrorException("ResourceNotFoundException", new Exception("nope")));
    SecretCache cache = newApiKeyCache(REFRESH);
    SecretFetchException e =
        assertThrows(SecretFetchException.class, () -> cache.get(cache.apiKey, tel));
    assertEquals("failed fetching AWS Secrets Manager secret \"key\": nope", e.getMessage());
    assertEquals("resource_not_found", e.reason);
    assertEquals(
        "none", tel.take(Telemetry.METRIC_FETCH_FAILED).get(0).get(Telemetry.LABEL_FALLBACK));
    assertTrue(results().isEmpty());
  }

  @Test
  public void staleValueIsNotUsedPastMaxStaleness() {
    fetcher.set("key", "v1", "ver1");
    SecretCache cache = newApiKeyCache(REFRESH);
    cache.get(cache.apiKey, tel);
    fetcher.fail("key", new Exception("down"));
    clock.addAndGet(MAX_STALENESS);
    assertThrows(SecretFetchException.class, () -> cache.get(cache.apiKey, tel));
  }

  @Test
  public void concurrentGetsShareAFetch() throws Exception {
    fetcher.set("key", "v1", "ver1");
    fetcher.gate = new CountDownLatch(1);
    SecretCache cache = newApiKeyCache(REFRESH);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<String> a = pool.submit(() -> cache.get(cache.apiKey, tel));
      Future<String> b = pool.submit(() -> cache.get(cache.apiKey, tel));
      waitUntil(() -> fetcher.calls("key") == 1);
      fetcher.gate.countDown();
      assertEquals("v1", a.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
      assertEquals("v1", b.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
      assertEquals(1, fetcher.calls("key"));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  public void refreshingServesStaleValueToOtherCallers() throws Exception {
    fetcher.set("key", "v1", "ver1");
    SecretCache cache = newApiKeyCache(REFRESH);
    cache.get(cache.apiKey, tel);

    fetcher.set("key", "v2", "ver2");
    fetcher.gate = new CountDownLatch(1);
    clock.addAndGet(REFRESH);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<String> refreshing = pool.submit(() -> cache.get(cache.apiKey, tel));
      waitUntil(() -> fetcher.calls("key") == 2);
      assertEquals("v1", cache.get(cache.apiKey, tel));
      fetcher.gate.countDown();
      assertEquals("v2", refreshing.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  public void callerTimingOutDoesNotCancelFetch() throws Exception {
    fetcher.set("key", "v1", "ver1");
    fetcher.gate = new CountDownLatch(1);
    SecretCache cache = newApiKeyCache(REFRESH);
    cache.fetchTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(10);
    SecretCache.FetchWaitTimeoutException e =
        assertThrows(
            SecretCache.FetchWaitTimeoutException.class, () -> cache.get(cache.apiKey, tel));
    assertEquals(
        "timed out after 10ms waiting for AWS Secrets Manager secret fetch", e.getMessage());

    fetcher.gate.countDown();
    waitUntil(() -> inFlight(cache) == null);
    assertEquals("v1", cache.get(cache.apiKey, tel));
    assertEquals(1, fetcher.calls("key"));
  }

  @Test
  public void timedOutWaitFallsBackToStaleValue() throws Exception {
    fetcher.set("key", "v1", "ver1");
    SecretCache cache = newApiKeyCache(0);
    cache.get(cache.apiKey, tel);
    tel.take(Telemetry.METRIC_CACHE_LOOKUP);

    fetcher.gate = new CountDownLatch(1);
    cache.fetchTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(10);
    clock.addAndGet(1);
    assertEquals("v1", cache.get(cache.apiKey, tel));
    assertEquals(Arrays.asList("miss_with_fallback"), results());
    fetcher.gate.countDown();
    waitUntil(() -> inFlight(cache) == null);
  }

  @Test
  public void failedFetchStartDoesNotWedgeCache() {
    fetcher.set("key", "v1", "ver1");
    SecretCache cache = newApiKeyCache(REFRESH);
    cache.executor =
        r -> {
          throw new RejectedExecutionException("no threads");
        };
    SecretFetchException e =
        assertThrows(SecretFetchException.class, () -> cache.get(cache.apiKey, tel));
    assertEquals("failed starting AWS Secrets Manager secret fetch", e.getMessage());
    assertNull(inFlight(cache));

    cache.executor = Runnable::run;
    assertEquals("v1", cache.get(cache.apiKey, tel));
  }

  @Test
  public void getNowRefreshesInBackgroundWhenDue() throws Exception {
    fetcher.set("key", "v1", "ver1");
    SecretCache cache = newApiKeyCache(0);
    cache.get(cache.apiKey, tel);
    long min = TimeUnit.SECONDS.toNanos(1);

    // A zero refresh interval is bounded by the minimum
    fetcher.set("key", "v2", "ver2");
    assertEquals("v1", cache.getNow(cache.apiKey, min, tel));
    assertNull(inFlight(cache));
    clock.addAndGet(min);
    assertEquals("v1", cache.getNow(cache.apiKey, min, tel));
    waitUntil(() -> "v2".equals(cache.getNow(cache.apiKey, min, tel)));
    assertEquals(2, fetcher.calls("key"));

    // A failed refresh keeps the current value until max staleness, and isn't retried until the
    // retry interval has passed
    fetcher.fail("key", new Exception("down"));
    clock.addAndGet(min);
    assertEquals("v2", cache.getNow(cache.apiKey, min, tel));
    waitUntil(() -> cache.apiKey.failedAt != null && inFlight(cache) == null);
    assertEquals("v2", cache.getNow(cache.apiKey, min, tel));
    assertEquals(3, fetcher.calls("key"));

    // Past max staleness, the value is kept until a refresh started after then fails, and then
    // there's no value until a refresh succeeds
    clock.addAndGet(MAX_STALENESS);
    assertEquals("v2", cache.getNow(cache.apiKey, min, tel));
    waitUntil(() -> fetcher.calls("key") == 4 && inFlight(cache) == null);
    assertNull(cache.getNow(cache.apiKey, min, tel));
    assertEquals(
        "failed fetching AWS Secrets Manager secret \"key\": down",
        cache.lastFailure(cache.apiKey));
    fetcher.set("key", "v3", "ver3");
    clock.addAndGet(RETRY);
    assertNull(cache.getNow(cache.apiKey, min, tel));
    waitUntil(() -> "v3".equals(cache.getNow(cache.apiKey, min, tel)));
    assertNull(cache.lastFailure(cache.apiKey));

    // A value that wasn't looked up for longer than the max staleness is kept while it's refreshed
    fetcher.set("key", "v4", "ver4");
    clock.addAndGet(MAX_STALENESS);
    assertEquals("v3", cache.getNow(cache.apiKey, min, tel));
    waitUntil(() -> "v4".equals(cache.getNow(cache.apiKey, min, tel)));
  }

  @Test
  public void bundledClientCertAndKey() {
    fetcher.set("bundle", "junk\n" + TEST_CERT_A + TEST_KEY_A, "ver1");
    SecretCache cache = newCache(new SecretIds("", "bundle", "bundle", ""), REFRESH);
    ClientCertPair pair = cache.get(cache.clientCert, tel);
    assertEquals(1, pair.chain.length);
    assertEquals(
        "CN=aws-secrets-manager-test-a", pair.chain[0].getSubjectX500Principal().getName());
    assertEquals("EC", pair.key.getAlgorithm());
    assertEquals(1, fetcher.calls("bundle"));
  }

  @Test
  public void separateClientCertAndKey() {
    fetcher.set("cert", TEST_CERT_A, "ver1");
    fetcher.set("key", TEST_KEY_A, "ver2");
    SecretCache cache = newCache(new SecretIds("", "cert", "key", ""), REFRESH);
    cache.get(cache.clientCert, tel);
    // Both are reported as the client cert in metrics
    assertEquals(
        Arrays.asList(
            tags("secret_type", "tls_client_cert", "fetch_path", "sdk", "result", "miss")),
        tel.take(Telemetry.METRIC_CACHE_LOOKUP));
  }

  @Test
  public void mismatchedClientCertAndKeyKeepThePreviousPair() {
    fetcher.set("cert", TEST_CERT_A, "ver1");
    fetcher.set("key", TEST_KEY_A, "ver1");
    SecretCache cache = newCache(new SecretIds("", "cert", "key", ""), REFRESH);
    cache.get(cache.clientCert, tel);

    // Fetched partway through rotating the separate secrets
    fetcher.set("cert", TEST_CERT_B, "ver2");
    clock.addAndGet(REFRESH);
    assertEquals(
        subject(cache.get(cache.clientCert, tel).chain[0]), "CN=aws-secrets-manager-test-a");
    assertEquals(
        "invalid_response",
        tel.take(Telemetry.METRIC_FETCH_FAILED).get(0).get(Telemetry.LABEL_FAILURE_REASON));

    fetcher.set("key", TEST_KEY_B, "ver2");
    clock.addAndGet(RETRY);
    assertEquals(
        subject(cache.get(cache.clientCert, tel).chain[0]), "CN=aws-secrets-manager-test-b");
  }

  @Test
  public void invalidClientCertAndKey() {
    String[][] cases = {
      {TEST_KEY_A, TEST_KEY_A, "no PEM certificate found in TLS client cert secret \"cert\""},
      {TEST_CERT_A, TEST_CERT_A, "no PEM private key found in TLS client key secret \"key\""},
      {
        TEST_CERT_A,
        TEST_KEY_B,
        "TLS client cert and key from AWS Secrets Manager secrets \"cert\" and \"key\" don't match"
      },
      {
        TEST_CERT_A,
        TEST_KEY_A_EC,
        "TLS client key secret \"key\" is not a PKCS#8 key: its PEM header is \"-----BEGIN EC"
            + " PRIVATE KEY-----\" instead of \"-----BEGIN PRIVATE KEY-----\". Convert it with:"
            + " openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem"
      },
      {
        "-----BEGIN CERTIFICATE-----\nbm9wZQ==\n-----END CERTIFICATE-----",
        TEST_KEY_A,
        null // Message from the JDK
      },
      {
        TEST_CERT_A,
        "-----BEGIN PRIVATE KEY-----\nbm9wZQ==\n-----END PRIVATE KEY-----",
        "invalid PEM private key in TLS client key secret \"key\""
      },
    };
    for (String[] c : cases) {
      fetcher.set("cert", c[0], "ver1");
      fetcher.set("key", c[1], "ver1");
      SecretCache cache = newCache(new SecretIds("", "cert", "key", ""), REFRESH);
      SecretFetchException e =
          assertThrows(SecretFetchException.class, () -> cache.get(cache.clientCert, tel));
      if (c[2] != null) {
        assertEquals(c[2], e.getMessage());
      } else {
        assertTrue(e.getMessage(), e.getMessage().startsWith("invalid PEM certificate in"));
      }
      assertEquals("invalid_response", e.reason);
    }
  }

  @Test
  public void invalidUtf8ApiKey() {
    fetcher.set("key", new byte[] {(byte) 0xff, (byte) 0xfe}, "ver1");
    SecretCache cache = newApiKeyCache(REFRESH);
    SecretFetchException e =
        assertThrows(SecretFetchException.class, () -> cache.get(cache.apiKey, tel));
    assertEquals("API key secret is not valid UTF-8", e.getMessage());
    assertEquals("invalid_response", e.reason);
  }

  @Test
  public void serverCaCert() {
    fetcher.set("ca", TEST_CERT_A + "\n" + TEST_CERT_B, "ver1");
    SecretCache cache = newCache(new SecretIds("", "", "", "ca"), REFRESH);
    X509Certificate[] certs = cache.get(cache.serverCaCert, tel);
    assertEquals(2, certs.length);

    fetcher.set("ca", "nothing here", "ver1");
    SecretCache cache2 = newCache(new SecretIds("", "", "", "ca"), REFRESH);
    SecretFetchException e =
        assertThrows(SecretFetchException.class, () -> cache2.get(cache2.serverCaCert, tel));
    assertEquals("no PEM certificates found in TLS server CA cert secret \"ca\"", e.getMessage());
  }

  private static String subject(X509Certificate cert) {
    return cert.getSubjectX500Principal().getName();
  }

  private static Object inFlight(SecretCache cache) {
    synchronized (cache.apiKey != null ? cache.apiKey : cache.clientCert) {
      return cache.apiKey != null ? cache.apiKey.inFlight : cache.clientCert.inFlight;
    }
  }
}
