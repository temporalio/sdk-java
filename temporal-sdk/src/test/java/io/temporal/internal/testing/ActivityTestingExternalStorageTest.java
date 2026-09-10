package io.temporal.internal.testing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInterface;
import io.temporal.api.common.v1.Payload;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.payload.storage.ExternalStorage;
import io.temporal.payload.storage.StorageDriver;
import io.temporal.payload.storage.StorageDriverClaim;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import io.temporal.payload.storage.StorageDriverStoreContext;
import io.temporal.testing.TestActivityEnvironment;
import io.temporal.testing.TestEnvironmentOptions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

public class ActivityTestingExternalStorageTest {

  private static final String DETAILS = "heartbeat-details";

  public @Rule Timeout timeout = Timeout.seconds(10);

  private final InMemoryDriver driver = new InMemoryDriver();
  private TestActivityEnvironment testEnvironment;

  @Before
  public void setUp() {
    testEnvironment =
        TestActivityEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(
                    WorkflowClientOptions.newBuilder()
                        .setExternalStorage(
                            ExternalStorage.newBuilder()
                                .setDriver(driver)
                                .setPayloadSizeThreshold(0)
                                .build())
                        .build())
                .build());
  }

  @After
  public void tearDown() throws Exception {
    testEnvironment.close();
  }

  @Test
  public void theHeartbeatListenerSeesDetailsThatWereOffloaded() {
    testEnvironment.registerActivitiesImplementations(new HeartbeatActivityImpl());
    AtomicReference<String> observed = new AtomicReference<>();
    testEnvironment.setActivityHeartbeatListener(String.class, observed::set);

    String result = testEnvironment.newActivityStub(TestActivity.class).activity1("input");

    assertEquals("input", result);
    assertTrue("expected the heartbeat details to be offloaded", driver.stores.get() > 0);
    assertEquals(DETAILS, observed.get());
  }

  @ActivityInterface
  public interface TestActivity {
    String activity1(String input);
  }

  public static class HeartbeatActivityImpl implements TestActivity {
    @Override
    public String activity1(String input) {
      Activity.getExecutionContext().heartbeat(DETAILS);
      return input;
    }
  }

  private static final class InMemoryDriver implements StorageDriver {
    private final Map<String, Payload> objects = new HashMap<>();
    final AtomicInteger stores = new AtomicInteger();
    private int counter = 0;

    @Override
    public String getName() {
      return "test-heartbeat";
    }

    @Override
    public String getType() {
      return "test.inmemory";
    }

    @Override
    public synchronized CompletableFuture<List<StorageDriverClaim>> store(
        StorageDriverStoreContext context, List<Payload> payloads) {
      stores.incrementAndGet();
      List<StorageDriverClaim> claims = new ArrayList<>();
      for (Payload payload : payloads) {
        String key = "obj-" + (counter++);
        objects.put(key, payload);
        claims.add(new StorageDriverClaim(Collections.singletonMap("key", key)));
      }
      return CompletableFuture.completedFuture(claims);
    }

    @Override
    public synchronized CompletableFuture<List<Payload>> retrieve(
        StorageDriverRetrieveContext context, List<StorageDriverClaim> claims) {
      List<Payload> payloads = new ArrayList<>();
      for (StorageDriverClaim claim : claims) {
        payloads.add(objects.get(claim.getClaimData().get("key")));
      }
      return CompletableFuture.completedFuture(payloads);
    }
  }
}
