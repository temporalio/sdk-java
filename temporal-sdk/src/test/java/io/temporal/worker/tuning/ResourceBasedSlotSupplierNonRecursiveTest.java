package io.temporal.worker.tuning;

import static org.junit.Assert.*;

import com.uber.m3.tally.NoopScope;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.internal.worker.SlotReservationData;
import io.temporal.internal.worker.TrackingSlotSupplier;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.internal.ExternalServiceTestConfigurator;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assume;
import org.junit.Test;

/**
 * Test to demonstrate the recursion issue in ResourceBasedSlotSupplier.scheduleSlotAcquisition()
 * where a long chain of futures is created when resources remain unavailable.
 */
public class ResourceBasedSlotSupplierNonRecursiveTest {

  static class TestResourceController extends ResourceBasedController {
    private final AtomicBoolean allow;
    private final AtomicInteger pidDecisionCallCount;

    public TestResourceController(AtomicBoolean allow) {
      super(
          ResourceBasedControllerOptions.newBuilder(0.5, 0.5)
              .setMemoryPGain(1)
              .setMemoryIGain(0)
              .setMemoryDGain(0)
              .setMemoryOutputThreshold(0)
              .setCpuPGain(1)
              .setCpuIGain(0)
              .setCpuDGain(0)
              .setCpuOutputThreshold(0)
              .build(),
          new JVMSystemResourceInfo());
      this.allow = allow;
      this.pidDecisionCallCount = new AtomicInteger(0);
    }

    @Override
    boolean pidDecision() {
      pidDecisionCallCount.incrementAndGet();
      return allow.get();
    }

    public int getPidDecisionCallCount() {
      return pidDecisionCallCount.get();
    }
  }

  /**
   * This test demonstrates the severe bug: even when resources become available, the future may
   * never resolve due to the extremely deep recursive chain. This is the actual reported bug.
   */
  @Test(timeout = 90000)
  public void testFutureNeverResolvesEvenAfterResourcesBecomeAvailable() throws Exception {
    AtomicBoolean allow = new AtomicBoolean(false);
    TestResourceController controller = new TestResourceController(allow);

    ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    ResourceBasedSlotSupplier<ActivitySlotInfo> supplier =
        ResourceBasedSlotSupplier.createForActivity(
            controller,
            ResourceBasedSlotOptions.newBuilder()
                .setMinimumSlots(0)
                .setMaximumSlots(10)
                .setRampThrottle(Duration.ZERO)
                .build(),
            scheduler);

    TrackingSlotSupplier<ActivitySlotInfo> tracking =
        new TrackingSlotSupplier<>(supplier, new NoopScope());

    // Reserve first slot
    SlotSupplierFuture firstFuture =
        tracking.reserveSlot(new SlotReservationData("tq", "id1", "bid1"));
    firstFuture.get(1, TimeUnit.SECONDS);

    // Try to reserve second slot - will build up recursive chain
    SlotSupplierFuture secondFuture =
        tracking.reserveSlot(new SlotReservationData("tq", "id2", "bid2"));

    // Build up a very deep chain - 30 seconds should create ~3000 recursive calls
    System.out.println("Building deep recursive chain for 30 seconds...");
    Thread.sleep(30000);

    int recursiveAttempts = controller.getPidDecisionCallCount();
    System.out.println("Built chain with " + recursiveAttempts + " recursive attempts");

    // Now ALLOW resources - this is the critical test
    System.out.println("Allowing resources NOW...");
    allow.set(true);

    // Try to get the result - this should complete quickly, but might NEVER complete
    // if the chain is too deep
    try {
      System.out.println("Waiting for future to complete (30 second timeout)...");
      long startTime = System.currentTimeMillis();
      SlotPermit permit = secondFuture.get(30, TimeUnit.SECONDS);
      long duration = System.currentTimeMillis() - startTime;

      if (permit != null) {
        System.out.println(
            "SUCCESS: Future completed in " + duration + "ms after allowing resources");
      } else {
        fail("Future completed but returned null permit");
      }
    } catch (TimeoutException e) {
      // This is the BUG - even though resources are now available, the future never completes
      System.out.println(
          "BUG REPRODUCED: Future did NOT complete even after 30 seconds of resources being"
              + " available!");
      System.out.println(
          "The recursive chain of "
              + recursiveAttempts
              + " futures is too deep and never resolves");
      fail(
          "Future never completed even after resources became available - this is the reported"
              + " bug!");
    } finally {
      SlotPermit ignored = secondFuture.abortReservation();
      scheduler.shutdownNow();
    }
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    String execute(String input);
  }

  @ActivityInterface
  public interface TestActivity {
    @ActivityMethod
    String process(String input);
  }

  public static class TestWorkflowImpl implements TestWorkflow {
    private final TestActivity activity =
        Workflow.newActivityStub(
            TestActivity.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30)).build());

    @Override
    public String execute(String input) {
      // Execute just one activity to keep test timing reasonable
      return activity.process(input);
    }
  }

  public static class TestActivityImpl implements TestActivity {
    @Override
    public String process(String input) {
      try {
        // Short activity duration to keep test fast
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return "Processed: " + input;
    }
  }

  @Test
  public void testEndToEndWorkerWithResourceStarvationRecovery() throws Exception {
    Assume.assumeTrue(
        "This test requires a real Temporal server to reproduce the recursion bug",
        SDKTestWorkflowRule.useExternalService);

    System.out.println(
        "=== End-to-End Worker Test with Resource Starvation Recovery (External Service) ===");

    // Create connections to real Temporal server
    WorkflowServiceStubs service =
        WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder()
                .setTarget(ExternalServiceTestConfigurator.getTemporalServiceAddress())
                .build());
    WorkflowClient client =
        WorkflowClient.newInstance(
            service, WorkflowClientOptions.newBuilder().setNamespace("default").build());
    WorkerFactory workerFactory = WorkerFactory.newInstance(client);

    // Create our own resource controller that we can control
    AtomicBoolean allowResources = new AtomicBoolean(false);
    TestResourceController resourceController = new TestResourceController(allowResources);

    // Create a custom WorkerTuner that uses our controlled resource controller
    WorkerTuner customTuner =
        new WorkerTuner() {
          @Override
          public SlotSupplier<WorkflowSlotInfo> getWorkflowTaskSlotSupplier() {
            return ResourceBasedSlotSupplier.createForWorkflow(
                resourceController,
                ResourceBasedSlotOptions.newBuilder()
                    .setMinimumSlots(-1) // Use negative to bypass default fallback
                    .setMaximumSlots(2) // Very restrictive limit to force queueing
                    .setRampThrottle(Duration.ZERO) // No delay = maximum recursion depth
                    .build());
          }

          @Override
          public SlotSupplier<ActivitySlotInfo> getActivityTaskSlotSupplier() {
            return ResourceBasedSlotSupplier.createForActivity(
                resourceController,
                ResourceBasedSlotOptions.newBuilder()
                    .setMinimumSlots(-1) // Use negative to bypass default fallback
                    .setMaximumSlots(2) // Very restrictive limit to force queueing
                    .setRampThrottle(Duration.ZERO) // No delay = maximum recursion depth
                    .build());
          }

          @Override
          public SlotSupplier<LocalActivitySlotInfo> getLocalActivitySlotSupplier() {
            return ResourceBasedSlotSupplier.createForLocalActivity(
                resourceController,
                ResourceBasedSlotOptions.newBuilder()
                    .setMinimumSlots(-1) // Use negative to bypass default fallback
                    .setMaximumSlots(2) // Very restrictive limit to force queueing
                    .setRampThrottle(Duration.ZERO) // No delay = maximum recursion depth
                    .build());
          }

          @Override
          public SlotSupplier<NexusSlotInfo> getNexusSlotSupplier() {
            return ResourceBasedSlotSupplier.createForNexus(
                resourceController,
                ResourceBasedSlotOptions.newBuilder()
                    .setMinimumSlots(-1) // Use negative to bypass default fallback
                    .setMaximumSlots(2) // Very restrictive limit to force queueing
                    .setRampThrottle(Duration.ZERO) // No delay = maximum recursion depth
                    .build());
          }
        };

    // Create worker with our custom tuner
    String taskQueue = "test-task-queue-" + System.currentTimeMillis();
    Worker worker =
        workerFactory.newWorker(
            taskQueue, WorkerOptions.newBuilder().setWorkerTuner(customTuner).build());

    worker.registerWorkflowImplementationTypes(TestWorkflowImpl.class);
    worker.registerActivitiesImplementations(new TestActivityImpl());

    workerFactory.start();

    try {
      // Start multiple workflows to create enough concurrent slot pressure
      TestWorkflow workflow =
          client.newWorkflowStub(
              TestWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setTaskQueue(taskQueue)
                  .setWorkflowId("test-workflow-" + System.currentTimeMillis())
                  .build());

      // Start workflow execution in background
      CompletableFuture<String> workflowResult =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return workflow.execute("test-input");
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              });

      // Let the system build up resource starvation for much longer to create extreme deep
      // recursion
      // We need VERY long starvation to build recursive chains deep enough to cause the bug
      long starvationStart = System.currentTimeMillis();
      System.out.println(
          "Building resource starvation for 30 seconds to create deep recursion... Started at: "
              + starvationStart);
      Thread.sleep(30000);

      int pidCallsBefore = resourceController.getPidDecisionCallCount();
      System.out.println("PID decisions during starvation: " + pidCallsBefore);
      System.out.println("Allow resources flag during starvation: " + allowResources.get());

      // The recursive bug should have built up thousands of nested futures by now

      // Now allow resources - workflow should complete quickly
      long allowTime = System.currentTimeMillis();
      System.out.println(
          "Allowing resources NOW at " + allowTime + " - workflow should complete quickly...");
      allowResources.set(true);
      System.out.println("Allow resources flag after setting: " + allowResources.get());

      assertEquals("Processed: test-input", workflowResult.get());

    } finally {
      workerFactory.shutdownNow();
      workerFactory.awaitTermination(1, TimeUnit.MINUTES);
      service.shutdownNow();
    }
  }
}
