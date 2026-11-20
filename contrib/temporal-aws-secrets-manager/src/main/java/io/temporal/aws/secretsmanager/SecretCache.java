package io.temporal.aws.secretsmanager;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TTL cache of the configured credentials that falls back to the last fetched value, up to the max
 * staleness old, when a refresh fails.
 *
 * <p>Fetches run on a shared pool of daemon threads, so that they aren't affected by callers timing
 * out or being interrupted, and don't keep the JVM running.
 */
final class SecretCache {
  private static final Logger log = LoggerFactory.getLogger(SecretCache.class);

  /**
   * After a failed refresh falls back to a stale value, wait this long before trying again so that
   * an outage doesn't add a fetch attempt to every credential lookup.
   */
  static final long FAILED_REFRESH_RETRY_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);

  private static final Pattern CERT_PEM_PATTERN =
      Pattern.compile(
          "-----BEGIN CERTIFICATE-----\r?\n.*?-----END CERTIFICATE-----", Pattern.DOTALL);
  private static final Pattern KEY_PEM_PATTERN =
      Pattern.compile(
          "-----BEGIN ((?:RSA |EC )?PRIVATE KEY)-----\r?\n(.*?)-----END \\1-----", Pattern.DOTALL);
  // Key algorithms that PKCS#8 keys are tried as, matching Netty's key loading used by envconfig
  private static final List<String> KEY_ALGORITHMS = Arrays.asList("RSA", "DSA", "EC");

  private static final AtomicInteger FETCH_THREAD_COUNT = new AtomicInteger();
  private static final ExecutorService FETCH_EXECUTOR =
      Executors.newCachedThreadPool(
          r -> {
            Thread t =
                new Thread(
                    r,
                    "temporal-aws-secrets-manager-fetch-" + FETCH_THREAD_COUNT.incrementAndGet());
            t.setDaemon(true);
            return t;
          });

  /** Configured secret IDs. Empty strings are unset. */
  static final class SecretIds {
    final String apiKey;
    final String clientCert;
    final String clientKey;
    final String serverCaCert;

    SecretIds(String apiKey, String clientCert, String clientKey, String serverCaCert) {
      this.apiKey = apiKey;
      this.clientCert = clientCert;
      this.clientKey = clientKey;
      this.serverCaCert = serverCaCert;
    }

    boolean hasTls() {
      return !clientCert.isEmpty() || !clientKey.isEmpty() || !serverCaCert.isEmpty();
    }

    boolean isEmpty() {
      return apiKey.isEmpty() && !hasTls();
    }
  }

  /** A client cert chain and its private key. */
  static final class ClientCertPair {
    final X509Certificate[] chain;
    final PrivateKey key;

    ClientCertPair(X509Certificate[] chain, PrivateKey key) {
      this.chain = chain;
      this.key = key;
    }
  }

  /** A failed fetch of one or more secrets, with its failure_reason label. */
  static final class SecretFetchException extends RuntimeException {
    final String reason;

    SecretFetchException(String message, String reason, @Nullable Throwable cause) {
      super(message, cause);
      this.reason = reason;
    }
  }

  /** Thrown when waiting for a fetch takes longer than the fetch timeout. */
  static final class FetchWaitTimeoutException extends RuntimeException {
    FetchWaitTimeoutException(String message) {
      super(message);
    }
  }

  @FunctionalInterface
  interface Parser<T> {
    /** Parses newly fetched secrets, in refs order, into the cached value. */
    T parse(List<Fetcher.SecretValue> secrets) throws FetchErrors.InvalidResponseException;
  }

  static final class SecretRef {
    final String id;
    final String secretType;

    SecretRef(String id, String secretType) {
      this.id = id;
      this.secretType = secretType;
    }
  }

  /**
   * One or more secrets that are fetched and replaced together, cached as a single parsed value.
   */
  static final class CacheEntry<T> {
    /** Distinct secret IDs. refs[0]'s secret type is used as the secret_type metric label. */
    final List<SecretRef> refs;

    final Parser<T> parser;

    // All fields below are guarded by this

    /** The fetch in progress, or null if none. */
    CompletableFuture<T> inFlight;

    /** In refs order, null until the first successful fetch. */
    List<String> versionIds;

    T value;
    long fetchedAt;

    /** Last failed fetch since the last successful fetch, or null if none. */
    Long failedAt;

    /** Why the last fetch failed, set and cleared along with failedAt. */
    @Nullable SecretFetchException lastFailure;

    CacheEntry(List<SecretRef> refs, Parser<T> parser) {
      this.refs = refs;
      this.parser = parser;
    }
  }

  final Fetcher fetcher;
  final long refreshIntervalNanos;
  final long maxStalenessNanos;

  /** How long callers wait for a fetch before falling back or failing. */
  volatile long fetchTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(Fetcher.FETCH_TIMEOUT_MILLIS);

  /** Monotonic nanoseconds, overridable in tests. */
  volatile LongSupplier now = System::nanoTime;

  /** Runs fetches, overridable in tests. */
  volatile Executor executor = FETCH_EXECUTOR;

  @Nullable final CacheEntry<String> apiKey;

  /** Client cert and key, fetched and replaced together to prevent tearing on rotations. */
  @Nullable final CacheEntry<ClientCertPair> clientCert;

  @Nullable final CacheEntry<X509Certificate[]> serverCaCert;

  SecretCache(Fetcher fetcher, SecretIds ids, long refreshIntervalNanos, long maxStalenessNanos) {
    this.fetcher = fetcher;
    this.refreshIntervalNanos = refreshIntervalNanos;
    this.maxStalenessNanos = maxStalenessNanos;
    this.apiKey =
        ids.apiKey.isEmpty()
            ? null
            : new CacheEntry<>(
                Collections.singletonList(new SecretRef(ids.apiKey, Telemetry.SECRET_TYPE_API_KEY)),
                SecretCache::parseApiKey);
    this.clientCert =
        ids.clientCert.isEmpty() ? null : newClientCertEntry(ids.clientCert, ids.clientKey);
    this.serverCaCert = ids.serverCaCert.isEmpty() ? null : newServerCaCertEntry(ids.serverCaCert);
  }

  /**
   * Gets an entry's value, fetching it if needed. Returned values are shared across callers and
   * must not be mutated.
   *
   * @throws SecretFetchException if the fetch failed and there's no usable previous value
   * @throws FetchWaitTimeoutException if the fetch timed out and there's no usable previous value
   */
  <T> T get(CacheEntry<T> entry, Telemetry tel) {
    long start;
    boolean canUseStale;
    T cached;
    CompletableFuture<T> fetch = null;
    boolean startFetch = false;
    String result = null;
    synchronized (entry) {
      start = now.getAsLong();
      boolean hasValue = entry.versionIds != null;
      canUseStale = hasValue && start - entry.fetchedAt < maxStalenessNanos;
      cached = entry.value;
      if ((hasValue && start - entry.fetchedAt < refreshIntervalNanos)
          // Another caller is refreshing, so the current value is still the latest known one
          || (canUseStale && entry.inFlight != null)) {
        result = "hit";
      } else if (canUseStale
          && entry.failedAt != null
          && start - entry.failedAt < FAILED_REFRESH_RETRY_INTERVAL_NANOS) {
        result = "miss_with_fallback";
      } else {
        // Wait for a fetch, starting one if none is in progress
        fetch = entry.inFlight;
        if (fetch == null) {
          fetch = new CompletableFuture<>();
          entry.inFlight = fetch;
          startFetch = true;
        }
      }
    }
    if (result != null) {
      recordLookup(entry, tel, result);
      return cached;
    }
    if (startFetch) {
      startFetch(entry, fetch, start, canUseStale, tel);
    }

    T value;
    try {
      value = fetch.get(fetchTimeoutNanos, TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (canUseStale) {
        recordLookup(entry, tel, "miss_with_fallback");
        return cached;
      }
      throw new FetchWaitTimeoutException(
          "interrupted while waiting for AWS Secrets Manager secret fetch");
    } catch (TimeoutException e) {
      if (canUseStale) {
        recordLookup(entry, tel, "miss_with_fallback");
        return cached;
      }
      throw new FetchWaitTimeoutException(
          "timed out after "
              + TimeUnit.NANOSECONDS.toMillis(fetchTimeoutNanos)
              + "ms waiting for AWS Secrets Manager secret fetch");
    } catch (ExecutionException e) {
      if (canUseStale) {
        recordLookup(entry, tel, "miss_with_fallback");
        return cached;
      }
      Throwable cause = e.getCause();
      if (cause instanceof SecretFetchException) {
        throw (SecretFetchException) cause;
      }
      throw new SecretFetchException(String.valueOf(cause), "other", cause);
    }
    recordLookup(entry, tel, "miss");
    return value;
  }

  /**
   * Returns an entry's current value without waiting, and starts refreshing it in the background if
   * it's due.
   *
   * <p>The value is refreshed at most every {@code minRefreshIntervalNanos}. If refreshing fails,
   * the current value keeps being returned until it's older than the max staleness, and then null
   * is returned until a refresh succeeds.
   *
   * <p>Since callers don't wait for a refresh, a value older than the max staleness is still
   * returned until a refresh has actually been tried and failed. Otherwise callers would get no
   * value while a healthy refresh is in progress, e.g. when the value was due for refresh right at
   * the max staleness, or wasn't looked up at all for longer than that.
   */
  @Nullable
  <T> T getNow(CacheEntry<T> entry, long minRefreshIntervalNanos, Telemetry tel) {
    long start;
    boolean canUseStale;
    T value;
    CompletableFuture<T> fetch = null;
    synchronized (entry) {
      start = now.getAsLong();
      canUseStale = entry.versionIds != null && start - entry.fetchedAt < maxStalenessNanos;
      // No value, or it's older than the max staleness and a refresh tried since then failed
      long expiresAt = entry.fetchedAt + maxStalenessNanos;
      boolean removed =
          entry.versionIds == null
              || (start - expiresAt >= 0
                  && entry.failedAt != null
                  && entry.failedAt - expiresAt >= 0);
      value = removed ? null : entry.value;
      if (entry.inFlight == null
          && start - entry.fetchedAt >= Math.max(refreshIntervalNanos, minRefreshIntervalNanos)
          && (entry.failedAt == null
              || start - entry.failedAt >= FAILED_REFRESH_RETRY_INTERVAL_NANOS)) {
        fetch = new CompletableFuture<>();
        entry.inFlight = fetch;
      }
    }
    if (fetch != null) {
      startFetch(entry, fetch, start, canUseStale, tel);
    }
    return value;
  }

  /** Why the entry's last fetch failed, or null if it didn't. */
  @Nullable
  String lastFailure(CacheEntry<?> entry) {
    synchronized (entry) {
      return entry.lastFailure != null ? entry.lastFailure.getMessage() : null;
    }
  }

  private <T> void startFetch(
      CacheEntry<T> entry,
      CompletableFuture<T> fetch,
      long start,
      boolean canUseStale,
      Telemetry tel) {
    try {
      executor.execute(() -> refresh(entry, fetch, start, canUseStale, tel));
    } catch (Throwable e) {
      // Usually a RejectedExecutionException, or an OutOfMemoryError if a thread can't be created.
      // Fail this fetch, including for callers already waiting on it, so that the next caller
      // starts a new one instead of the entry never being refreshed again. Errors are still
      // rethrown once the cache is consistent.
      synchronized (entry) {
        entry.inFlight = null;
      }
      fetch.completeExceptionally(
          new SecretFetchException("failed starting AWS Secrets Manager secret fetch", "other", e));
      if (e instanceof Error) {
        throw (Error) e;
      }
    }
  }

  /** Fetches an entry's secrets and records the outcome in the cache. */
  private <T> void refresh(
      CacheEntry<T> entry,
      CompletableFuture<T> fetch,
      long start,
      boolean canUseStale,
      Telemetry tel) {
    T value = null;
    List<String> versionIds = null;
    SecretFetchException failure = null;
    Error error = null;
    try {
      List<Fetcher.SecretValue> secrets = fetchAll(entry);
      value = parse(entry, secrets);
      versionIds = new ArrayList<>(secrets.size());
      for (Fetcher.SecretValue secret : secrets) {
        versionIds.add(secret.versionId);
      }
    } catch (SecretFetchException e) {
      failure = e;
    } catch (Throwable e) {
      // Shouldn't happen, but don't leave the fetch pending. E.g. a LinkageError from a mismatched
      // AWS SDK version. Errors are still rethrown below once the fetch is complete.
      failure = new SecretFetchException(e.toString(), "other", e);
      if (e instanceof Error) {
        error = (Error) e;
      }
    }

    List<String> prevVersionIds;
    long prevFetchedAt;
    boolean prevFailed;
    synchronized (entry) {
      prevVersionIds = entry.versionIds;
      prevFetchedAt = entry.fetchedAt;
      prevFailed = entry.failedAt != null;
      if (failure != null) {
        entry.failedAt = start;
        entry.lastFailure = failure;
      } else {
        entry.value = value;
        entry.versionIds = versionIds;
        entry.fetchedAt = start;
        entry.failedAt = null;
        entry.lastFailure = null;
      }
      entry.inFlight = null;
    }

    // Record telemetry outside the lock, and complete the fetch last so that the fetch's metrics
    // and logs are recorded by the time waiters return
    try {
      long latencyNanos = now.getAsLong() - start;
      if (failure != null) {
        recordFailure(entry, failure, latencyNanos, start - prevFetchedAt, canUseStale, tel);
      } else {
        recordSuccess(entry, versionIds, prevVersionIds, prevFailed, latencyNanos, tel);
      }
    } catch (RuntimeException e) {
      log.warn("Failed recording AWS Secrets Manager telemetry", e);
    } finally {
      if (failure != null) {
        fetch.completeExceptionally(failure);
      } else {
        fetch.complete(value);
      }
    }
    if (error != null) {
      throw error;
    }
  }

  private <T> List<Fetcher.SecretValue> fetchAll(CacheEntry<T> entry) {
    List<Fetcher.SecretValue> secrets = new ArrayList<>(entry.refs.size());
    for (SecretRef ref : entry.refs) {
      try {
        secrets.add(fetcher.fetch(ref.id));
      } catch (Exception e) {
        throw new SecretFetchException(
            "failed fetching AWS Secrets Manager secret \""
                + ref.id
                + "\": "
                + (e.getMessage() != null ? e.getMessage() : e.toString()),
            Telemetry.classifyFailure(e),
            e);
      }
    }
    return secrets;
  }

  private static <T> T parse(CacheEntry<T> entry, List<Fetcher.SecretValue> secrets) {
    try {
      return entry.parser.parse(secrets);
    } catch (FetchErrors.InvalidResponseException e) {
      throw new SecretFetchException(e.getMessage(), "invalid_response", e);
    }
  }

  private Map<String, String> baseTags(CacheEntry<?> entry) {
    Map<String, String> tags = new HashMap<>();
    tags.put(Telemetry.LABEL_SECRET_TYPE, entry.refs.get(0).secretType);
    tags.put(Telemetry.LABEL_FETCH_PATH, fetcher.path());
    return tags;
  }

  private void recordLookup(CacheEntry<?> entry, Telemetry tel, String result) {
    Map<String, String> tags = baseTags(entry);
    tags.put(Telemetry.LABEL_RESULT, result);
    tel.cacheLookup(tags);
  }

  private void recordFailure(
      CacheEntry<?> entry,
      SecretFetchException err,
      long latencyNanos,
      long ageNanos,
      boolean canUseStale,
      Telemetry tel) {
    Map<String, String> latencyTags = baseTags(entry);
    latencyTags.put(Telemetry.LABEL_OUTCOME, "failure");
    tel.fetchLatency(latencyTags, latencyNanos);
    Map<String, String> failedTags = baseTags(entry);
    failedTags.put(Telemetry.LABEL_FAILURE_REASON, err.reason);
    failedTags.put(Telemetry.LABEL_FALLBACK, canUseStale ? "stale" : "none");
    tel.fetchFailed(failedTags);
    if (canUseStale) {
      for (SecretRef ref : entry.refs) {
        log.warn(
            "Failed refreshing AWS Secrets Manager secret, using previously fetched value "
                + "(secret_id={}, secret_type={}, error={}, age={}, max_staleness={})",
            ref.id,
            ref.secretType,
            err.getMessage(),
            Duration.ofNanos(ageNanos),
            Duration.ofNanos(maxStalenessNanos));
      }
    }
  }

  private void recordSuccess(
      CacheEntry<?> entry,
      List<String> versionIds,
      @Nullable List<String> prevVersionIds,
      boolean prevFailed,
      long latencyNanos,
      Telemetry tel) {
    Map<String, String> tags = baseTags(entry);
    tags.put(Telemetry.LABEL_OUTCOME, "success");
    tel.fetchLatency(tags, latencyNanos);
    for (int i = 0; i < entry.refs.size(); i++) {
      SecretRef ref = entry.refs.get(i);
      if (prevVersionIds == null) {
        log.info(
            "Fetched AWS Secrets Manager secret "
                + "(secret_id={}, secret_type={}, fetch_path={}, version_id={})",
            ref.id,
            ref.secretType,
            fetcher.path(),
            versionIds.get(i));
      } else if (prevFailed || !versionIds.get(i).equals(prevVersionIds.get(i))) {
        log.info(
            "Fetched updated AWS Secrets Manager secret "
                + "(secret_id={}, secret_type={}, previous_version_id={}, version_id={})",
            ref.id,
            ref.secretType,
            prevVersionIds.get(i),
            versionIds.get(i));
      }
    }
  }

  private static String parseApiKey(List<Fetcher.SecretValue> secrets)
      throws FetchErrors.InvalidResponseException {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(secrets.get(0).value))
          .toString();
    } catch (CharacterCodingException e) {
      throw new FetchErrors.InvalidResponseException("API key secret is not valid UTF-8");
    }
  }

  /**
   * Entry for the client cert and key, which may be the same secret if bundled. Each secret's
   * relevant PEM blocks are extracted, so a bundled secret can be used as both. Keys must be
   * PKCS#8, like keys in envconfig. A newly fetched pair that doesn't match (e.g. fetched partway
   * through rotating separate cert and key secrets) is treated as a failed fetch, so the previous
   * pair keeps being used.
   */
  private static CacheEntry<ClientCertPair> newClientCertEntry(String certId, String keyId) {
    List<SecretRef> refs = new ArrayList<>();
    refs.add(new SecretRef(certId, Telemetry.SECRET_TYPE_TLS_CLIENT_CERT));
    if (!keyId.equals(certId)) {
      refs.add(new SecretRef(keyId, Telemetry.SECRET_TYPE_TLS_CLIENT_KEY));
    }
    return new CacheEntry<>(
        refs,
        secrets -> {
          X509Certificate[] chain = parseCerts(secrets.get(0).value, "TLS client cert", certId);
          if (chain.length == 0) {
            throw new FetchErrors.InvalidResponseException(
                "no PEM certificate found in TLS client cert secret \"" + certId + "\"");
          }
          PrivateKey key = parseKey(secrets.get(secrets.size() - 1).value, keyId);
          if (!keyMatchesCert(key, chain[0])) {
            throw new FetchErrors.InvalidResponseException(
                "TLS client cert and key from AWS Secrets Manager secrets \""
                    + certId
                    + "\" and \""
                    + keyId
                    + "\" don't match");
          }
          return new ClientCertPair(chain, key);
        });
  }

  private static CacheEntry<X509Certificate[]> newServerCaCertEntry(String secretId) {
    return new CacheEntry<>(
        Collections.singletonList(
            new SecretRef(secretId, Telemetry.SECRET_TYPE_TLS_SERVER_CA_CERT)),
        secrets -> {
          X509Certificate[] certs =
              parseCerts(secrets.get(0).value, "TLS server CA cert", secretId);
          if (certs.length == 0) {
            throw new FetchErrors.InvalidResponseException(
                "no PEM certificates found in TLS server CA cert secret \"" + secretId + "\"");
          }
          return certs;
        });
  }

  private static X509Certificate[] parseCerts(byte[] secret, String what, String secretId)
      throws FetchErrors.InvalidResponseException {
    Matcher m = CERT_PEM_PATTERN.matcher(new String(secret, StandardCharsets.UTF_8));
    List<X509Certificate> certs = new ArrayList<>();
    try {
      CertificateFactory factory = CertificateFactory.getInstance("X.509");
      while (m.find()) {
        certs.add(
            (X509Certificate)
                factory.generateCertificate(
                    new ByteArrayInputStream(m.group().getBytes(StandardCharsets.US_ASCII))));
      }
    } catch (GeneralSecurityException e) {
      throw new FetchErrors.InvalidResponseException(
          "invalid PEM certificate in " + what + " secret \"" + secretId + "\": " + e.getMessage(),
          e);
    }
    return certs.toArray(new X509Certificate[0]);
  }

  private static PrivateKey parseKey(byte[] secret, String keyId)
      throws FetchErrors.InvalidResponseException {
    Matcher m = KEY_PEM_PATTERN.matcher(new String(secret, StandardCharsets.UTF_8));
    if (!m.find()) {
      throw new FetchErrors.InvalidResponseException(
          "no PEM private key found in TLS client key secret \"" + keyId + "\"");
    }
    if (!m.group(1).equals("PRIVATE KEY")) {
      throw new FetchErrors.InvalidResponseException(
          "TLS client key secret \""
              + keyId
              + "\" is not a PKCS#8 key: its PEM header is \"-----BEGIN "
              + m.group(1)
              + "-----\" instead of \"-----BEGIN PRIVATE KEY-----\". Convert it with: openssl "
              + "pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem");
    }
    byte[] der;
    try {
      der = Base64.getMimeDecoder().decode(m.group(2));
    } catch (IllegalArgumentException e) {
      throw new FetchErrors.InvalidResponseException(
          "invalid PEM private key in TLS client key secret \"" + keyId + "\"", e);
    }
    PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
    for (String algorithm : KEY_ALGORITHMS) {
      try {
        return KeyFactory.getInstance(algorithm).generatePrivate(spec);
      } catch (GeneralSecurityException e) {
        // Try the next algorithm
      }
    }
    throw new FetchErrors.InvalidResponseException(
        "invalid PEM private key in TLS client key secret \"" + keyId + "\"");
  }

  /** Whether a private key matches a cert's public key, checked by signing and verifying. */
  private static boolean keyMatchesCert(PrivateKey key, X509Certificate cert) {
    String algorithm;
    switch (key.getAlgorithm()) {
      case "RSA":
        algorithm = "SHA256withRSA";
        break;
      case "EC":
        algorithm = "SHA256withECDSA";
        break;
      case "DSA":
        algorithm = "SHA256withDSA";
        break;
      default:
        return false;
    }
    byte[] data = "temporal-aws-secrets-manager".getBytes(StandardCharsets.US_ASCII);
    try {
      Signature signer = Signature.getInstance(algorithm);
      signer.initSign(key);
      signer.update(data);
      byte[] signature = signer.sign();
      Signature verifier = Signature.getInstance(algorithm);
      verifier.initVerify(cert.getPublicKey());
      verifier.update(data);
      return verifier.verify(signature);
    } catch (GeneralSecurityException e) {
      // e.g. an EC key with an RSA cert
      return false;
    }
  }
}
