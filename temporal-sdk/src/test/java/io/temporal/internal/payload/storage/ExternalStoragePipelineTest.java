package io.temporal.internal.payload.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.common.v1.Payload;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.payload.storage.ExternalStorageOptions;
import io.temporal.payload.storage.StorageDriver;
import io.temporal.payload.storage.StorageDriverClaim;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import io.temporal.payload.storage.StorageDriverStoreContext;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;

/**
 * End-to-end round trip through the worker and client pipelines: with external storage configured
 * and a zero threshold (offload everything), a workflow that passes payloads through an activity
 * must still complete correctly, and the driver must have actually stored and restored payloads.
 */
public class ExternalStoragePipelineTest {

  private static final InMemoryDriver DRIVER = new InMemoryDriver("test");

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(EchoWorkflowImpl.class)
          .setActivityImplementations(new EchoActivityImpl())
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setExternalStorage(
                      ExternalStorageOptions.newBuilder()
                          .setDriver(DRIVER)
                          .setPayloadSizeThreshold(0)
                          .build())
                  .build())
          .build();

  @Test
  public void payloadsRoundTripThroughStorage() {
    EchoWorkflow workflow = testWorkflowRule.newWorkflowStub(EchoWorkflow.class);

    String result = workflow.run("hello");

    assertEquals("echo: hello", result);
    assertTrue("expected the driver to have stored payloads", DRIVER.stores.get() > 0);
    assertTrue("expected the driver to have restored payloads", DRIVER.retrieves.get() > 0);
  }

  @WorkflowInterface
  public interface EchoWorkflow {
    @WorkflowMethod
    String run(String input);
  }

  @ActivityInterface
  public interface EchoActivity {
    @ActivityMethod
    String echo(String input);
  }

  public static class EchoWorkflowImpl implements EchoWorkflow {
    private final EchoActivity activity =
        Workflow.newActivityStub(
            EchoActivity.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build());

    @Override
    public String run(String input) {
      return activity.echo(input);
    }
  }

  public static class EchoActivityImpl implements EchoActivity {
    @Override
    public String echo(String input) {
      Activity.getExecutionContext().heartbeat("heartbeat: " + input);
      return "echo: " + input;
    }
  }

  private static final class InMemoryDriver implements StorageDriver {
    private final String name;
    private final Map<String, Payload> objects = new ConcurrentHashMap<>();
    final AtomicInteger stores = new AtomicInteger();
    final AtomicInteger retrieves = new AtomicInteger();
    private final AtomicInteger counter = new AtomicInteger();

    InMemoryDriver(String name) {
      this.name = name;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public String getType() {
      return "test.inmemory";
    }

    @Override
    public CompletableFuture<List<StorageDriverClaim>> store(
        StorageDriverStoreContext context, List<Payload> payloads) {
      List<StorageDriverClaim> claims = new ArrayList<>(payloads.size());
      for (Payload payload : payloads) {
        stores.incrementAndGet();
        String key = name + "-" + counter.incrementAndGet();
        objects.put(key, payload);
        claims.add(new StorageDriverClaim(Collections.singletonMap("key", key)));
      }
      return CompletableFuture.completedFuture(claims);
    }

    @Override
    public CompletableFuture<List<Payload>> retrieve(
        StorageDriverRetrieveContext context, List<StorageDriverClaim> claims) {
      List<Payload> payloads = new ArrayList<>(claims.size());
      for (StorageDriverClaim claim : claims) {
        retrieves.incrementAndGet();
        payloads.add(objects.get(claim.getClaimData().get("key")));
      }
      return CompletableFuture.completedFuture(payloads);
    }
  }
}
