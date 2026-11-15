package io.temporal.aws.secretsmanager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

final class TestHelpers {
  private TestHelpers() {}

  // Self-signed EC test certs, generated just for this module's tests and deliberately expired so
  // they can't be used as real credentials. They're only parsed and matched against their keys,
  // never used for TLS. TEST_KEY_A_EC is TEST_KEY_A in the traditional EC format.

  static final String TEST_CERT_A =
      "-----BEGIN CERTIFICATE-----\n"
          + "MIIBnjCCAUWgAwIBAgIUDqZuJ4EivyOw4rVmjlePA7udGhUwCgYIKoZIzj0EAwIw\n"
          + "JTEjMCEGA1UEAwwaYXdzLXNlY3JldHMtbWFuYWdlci10ZXN0LWEwHhcNMDAwMTAx\n"
          + "MDAwMDAwWhcNMDAwMTAyMDAwMDAwWjAlMSMwIQYDVQQDDBphd3Mtc2VjcmV0cy1t\n"
          + "YW5hZ2VyLXRlc3QtYTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABPYjw38P+CZ7\n"
          + "5Gn5L4yZ7QfLq1MNax7wuqmksAu+SSwNwmjiNBM0LpvEHZGaB5uun4JVwPDX2Caw\n"
          + "dOzRMMjkJfGjUzBRMB0GA1UdDgQWBBQqh6LjSKZlTFBKw8fPrKcMppVc0TAfBgNV\n"
          + "HSMEGDAWgBQqh6LjSKZlTFBKw8fPrKcMppVc0TAPBgNVHRMBAf8EBTADAQH/MAoG\n"
          + "CCqGSM49BAMCA0cAMEQCIBOvMndUhN/E4pi3ohOrZhZ98yUG+5wGkPirzSgiyrGA\n"
          + "AiB67ITqPqGBwT1mOTKnHa0R801etROwSp8EicjU65Xi0A==\n"
          + "-----END CERTIFICATE-----\n";

  static final String TEST_KEY_A =
      "-----BEGIN PRIVATE KEY-----\n"
          + "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQguBxMHVTJpKDUQdXp\n"
          + "lUqPRKgtTMRVEVjYJbRybzKMYoehRANCAAT2I8N/D/gme+Rp+S+Mme0Hy6tTDWse\n"
          + "8LqppLALvkksDcJo4jQTNC6bxB2Rmgebrp+CVcDw19gmsHTs0TDI5CXx\n"
          + "-----END PRIVATE KEY-----\n";

  static final String TEST_KEY_A_EC =
      "-----BEGIN EC PRIVATE KEY-----\n"
          + "MHcCAQEEILgcTB1UyaSg1EHV6ZVKj0SoLUzEVRFY2CW0cm8yjGKHoAoGCCqGSM49\n"
          + "AwEHoUQDQgAE9iPDfw/4JnvkafkvjJntB8urUw1rHvC6qaSwC75JLA3CaOI0EzQu\n"
          + "m8QdkZoHm66fglXA8NfYJrB07NEwyOQl8Q==\n"
          + "-----END EC PRIVATE KEY-----\n";

  static final String TEST_CERT_B =
      "-----BEGIN CERTIFICATE-----\n"
          + "MIIBoDCCAUWgAwIBAgIUJkXwBu3Peti5+WEGvCfk3YQYnFgwCgYIKoZIzj0EAwIw\n"
          + "JTEjMCEGA1UEAwwaYXdzLXNlY3JldHMtbWFuYWdlci10ZXN0LWIwHhcNMDAwMTAx\n"
          + "MDAwMDAwWhcNMDAwMTAyMDAwMDAwWjAlMSMwIQYDVQQDDBphd3Mtc2VjcmV0cy1t\n"
          + "YW5hZ2VyLXRlc3QtYjBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABFeVEzhEhUbs\n"
          + "l4sgc8vkm5Lfgemqc5h2EirK5OAna/9/Yd4feUw8JYK6VKfIeJJBVeaKjJbihaTY\n"
          + "aXA9zg+PBMCjUzBRMB0GA1UdDgQWBBTBGCIL3D2zvsou4vFsR2lsmwTtdjAfBgNV\n"
          + "HSMEGDAWgBTBGCIL3D2zvsou4vFsR2lsmwTtdjAPBgNVHRMBAf8EBTADAQH/MAoG\n"
          + "CCqGSM49BAMCA0kAMEYCIQDnP+D+kffMeiRoerQaNplgBlaH++eFY+MPLKMLMuRc\n"
          + "UAIhAKcdRK2djsmk2QP7q7yc1js9HeDONluoo2WaWLMilAyk\n"
          + "-----END CERTIFICATE-----\n";

  static final String TEST_KEY_B =
      "-----BEGIN PRIVATE KEY-----\n"
          + "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgsjA2Q9nct3Mb+KOR\n"
          + "akioJrOmqRw6/SzLSucTqopek9qhRANCAARXlRM4RIVG7JeLIHPL5JuS34HpqnOY\n"
          + "dhIqyuTgJ2v/f2HeH3lMPCWCulSnyHiSQVXmioyW4oWk2GlwPc4PjwTA\n"
          + "-----END PRIVATE KEY-----\n";

  /** Polls until {@code cond} is true, failing if it isn't within 5 seconds. */
  static void waitUntil(BooleanSupplier cond) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!cond.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not met within 5s");
      }
      Thread.sleep(10);
    }
  }

  /**
   * Returns configured values, counting fetches. A value may be an exception to throw. If {@code
   * gate} is set, fetches wait for it.
   */
  static final class FakeFetcher implements Fetcher {
    final Map<String, Object> values = new ConcurrentHashMap<>();
    final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    volatile CountDownLatch gate;
    private final String path;

    FakeFetcher() {
      this(Telemetry.FETCH_PATH_SDK);
    }

    FakeFetcher(String path) {
      this.path = path;
    }

    void set(String secretId, String value, String versionId) {
      set(secretId, value.getBytes(java.nio.charset.StandardCharsets.UTF_8), versionId);
    }

    void set(String secretId, byte[] value, String versionId) {
      values.put(secretId, new SecretValue(value, versionId));
    }

    void fail(String secretId, Exception err) {
      values.put(secretId, err);
    }

    int calls(String secretId) {
      AtomicInteger n = calls.get(secretId);
      return n != null ? n.get() : 0;
    }

    @Override
    public String path() {
      return path;
    }

    @Override
    public SecretValue fetch(String secretId) throws Exception {
      calls.computeIfAbsent(secretId, k -> new AtomicInteger()).incrementAndGet();
      CountDownLatch g = gate;
      if (g != null && !g.await(5, TimeUnit.SECONDS)) {
        throw new AssertionError("gate not opened");
      }
      Object value = values.get(secretId);
      if (value == null) {
        throw new Exception("no fake value for " + secretId);
      }
      if (value instanceof Exception) {
        throw (Exception) value;
      }
      return (SecretValue) value;
    }
  }

  /** A recorded metric update. */
  static final class MetricUpdate {
    final String name;
    final Map<String, String> tags;

    MetricUpdate(String name, Map<String, String> tags) {
      this.name = name;
      this.tags = tags;
    }
  }

  /** Telemetry that records metric updates. */
  static final class RecordingTelemetry extends Telemetry {
    final List<MetricUpdate> updates = new ArrayList<>();

    RecordingTelemetry() {
      super(null);
    }

    @Override
    synchronized void cacheLookup(Map<String, String> tags) {
      updates.add(new MetricUpdate(METRIC_CACHE_LOOKUP, tags));
    }

    @Override
    synchronized void fetchFailed(Map<String, String> tags) {
      updates.add(new MetricUpdate(METRIC_FETCH_FAILED, tags));
    }

    @Override
    synchronized void fetchLatency(Map<String, String> tags, long nanos) {
      updates.add(new MetricUpdate(METRIC_FETCH_LATENCY, tags));
    }

    /** Returns and clears the tags of each update of the given metric. */
    synchronized List<Map<String, String>> take(String name) {
      List<Map<String, String>> taken = new ArrayList<>();
      updates.removeIf(
          u -> {
            if (u.name.equals(name)) {
              taken.add(u.tags);
              return true;
            }
            return false;
          });
      return taken;
    }
  }
}
