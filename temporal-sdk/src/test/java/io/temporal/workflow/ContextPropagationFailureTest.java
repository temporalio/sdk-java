package io.temporal.workflow;

import static org.junit.Assert.assertTrue;

import io.temporal.api.common.v1.Payload;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;

public class ContextPropagationFailureTest {
  private static final CountDownLatch failures = new CountDownLatch(2);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setContextPropagators(Collections.singletonList(new FailingPropagator()))
                  .build())
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setDefaultDeadlockDetectionTimeout(TimeUnit.MINUTES.toMillis(1))
                  .build())
          .build();

  /**
   * A context propagator that fails while a Workflow thread starts must end the thread right away,
   * so the Workflow Task fails and is retried instead of waiting for deadlock detection.
   */
  @Test
  public void contextPropagationFailureEndsThread() throws InterruptedException {
    WorkflowClient.start(testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow.class)::run);

    assertTrue(failures.await(5, TimeUnit.SECONDS));
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    void run();
  }

  public static class TestWorkflowImpl implements TestWorkflow {
    @Override
    public void run() {}
  }

  private static class FailingPropagator implements ContextPropagator {
    @Override
    public String getName() {
      return "failing";
    }

    @Override
    public Map<String, Payload> serializeContext(Object context) {
      return Collections.singletonMap(
          "context", DefaultDataConverter.STANDARD_INSTANCE.toPayload(context).get());
    }

    @Override
    public Object deserializeContext(Map<String, Payload> context) {
      return DefaultDataConverter.STANDARD_INSTANCE.fromPayload(
          context.get("context"), String.class, String.class);
    }

    @Override
    public Object getCurrentContext() {
      return "context";
    }

    @Override
    public void setCurrentContext(Object context) {
      failures.countDown();
      throw new RuntimeException("context propagation failed");
    }
  }
}
