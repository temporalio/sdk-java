package io.temporal.workflow.versionTests;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.Test;
import org.slf4j.Logger;

/**
 * Mirrors app/src/main/kotlin/io/temporal/samples/update_nde/GreetingWorkflow.kt from
 * gauravthadani/samples-kotlin and replays a history where update completions are interleaved with
 * version markers.
 */
public class GetVersionInterleavedUpdateReplayTest {
  private static final String HISTORY_RESOURCE =
      "testGetVersionInterleavedUpdateReplayHistory.json";
  private static final String EXPECTED_NON_DETERMINISTIC_MESSAGE =
      "[TMPRL1100] getVersion call before the existing version marker event. The most probable cause is retroactive addition of a getVersion call with an existing 'changeId'";
  private static final String EXPECTED_NON_DETERMINISTIC_FRAGMENT =
      "io.temporal.worker.NonDeterministicException: " + EXPECTED_NON_DETERMINISTIC_MESSAGE;

  @Test
  public void testReplayHistory() {
    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                WorkflowReplayer.replayWorkflowExecutionFromResource(
                    HISTORY_RESOURCE, GreetingWorkflowImpl.class));
    assertTrue(thrown.getMessage().contains(EXPECTED_NON_DETERMINISTIC_FRAGMENT));
  }

  public static class Request {
    private final String name;
    private final OffsetDateTime date;

    public Request(String name, OffsetDateTime date) {
      this.name = name;
      this.date = date;
    }

    public String getName() {
      return name;
    }

    public OffsetDateTime getDate() {
      return date;
    }
  }

  @WorkflowInterface
  public interface GreetingWorkflow {
    @WorkflowMethod
    String greeting(String name);

    @UpdateMethod
    String notify(String name);
  }

  public static class GreetingWorkflowImpl implements GreetingWorkflow {
    private final Logger logger = Workflow.getLogger(GreetingWorkflow.class);

    public GreetingWorkflowImpl() {
      logger.info("Workflow is initialized");
    }

    private GreetingActivities getActivities() {
      return Workflow.newActivityStub(
          GreetingActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofSeconds(30))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
              .build());
    }

    @Override
    public String greeting(String name) {
      logger.info("Workflow started");

      Workflow.getVersion("ChangeId1", 0, 1);
      Workflow.getVersion("ChangeId2", 0, 1);

      Workflow.await(() -> false);
      return getActivities().composeGreeting("hello", name);
    }

    @Override
    public String notify(String name) {
      logger.info("Signal received: {}", name);
      Workflow.sideEffect(UUID.class, UUID::randomUUID);
      return "works";
    }
  }

  public static class GreetingActivitiesImpl implements GreetingActivities {
    @Override
    public String composeGreeting(String greeting, String name) {
      System.out.println("Greeting started: " + greeting);
      return greeting + ", " + name + "!";
    }
  }

  @ActivityInterface
  public interface GreetingActivities {
    @ActivityMethod(name = "greet")
    String composeGreeting(String greeting, String name);
  }
}
