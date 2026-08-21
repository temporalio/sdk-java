package io.temporal.workflow;

import io.temporal.testing.WorkflowReplayer;
import java.util.Arrays;
import java.util.Collection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class PromiseTimedGetCancellationReplayTest {

  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object[]> parameters() {
    return Arrays.asList(
        new Object[][] {
          {
            "promiseGetTimeoutCancellationLegacyAwaitLegacy.json",
            PromiseTimedGetCancellationTest.ReplayWorkflowImpl.class
          },
          {
            "promiseGetTimeoutCancellationLegacyAwaitCancel.json",
            PromiseTimedGetCancellationTest.ReplayWorkflowImpl.class
          },
          {
            "promiseGetTimeoutCancellationDetachedAwaitLegacy.json",
            PromiseTimedGetCancellationTest.ReplayWorkflowImpl.class
          },
          {
            "promiseGetTimeoutCancellationDetachedAwaitCancel.json",
            PromiseTimedGetCancellationTest.ReplayWorkflowImpl.class
          },
          {
            "promiseGetTimeoutCancellationCompletionAwaitLegacy.json",
            PromiseTimedGetCancellationTest.ControlledPromiseWorkflowImpl.class
          },
          {
            "promiseGetTimeoutCancellationCompletionAwaitCancel.json",
            PromiseTimedGetCancellationTest.ControlledPromiseWorkflowImpl.class
          },
          {
            "promiseGetTimeoutCancellationChildAwaitLegacy.json",
            PromiseTimedGetCancellationTest.ChildStartCancellationWorkflowImpl.class
          },
          {
            "promiseGetTimeoutCancellationChildAwaitCancel.json",
            PromiseTimedGetCancellationTest.ChildStartCancellationWorkflowImpl.class
          },
          {
            "promiseGetTimeoutCancellationActivityLegacyAwaitLegacy.json",
            PromiseTimedGetCancellationTest.ActivityCancellationWorkflowImpl.class
          },
          {
            "promiseGetTimeoutCancellationActivityLegacyAwaitCancel.json",
            PromiseTimedGetCancellationTest.ActivityCancellationWorkflowImpl.class
          },
          {
            "promiseGetTimeoutCancellationActivityDetachedAwaitLegacy.json",
            PromiseTimedGetCancellationTest.ActivityCancellationWorkflowImpl.class
          },
          {
            "promiseGetTimeoutCancellationActivityDetachedAwaitCancel.json",
            PromiseTimedGetCancellationTest.ActivityCancellationWorkflowImpl.class
          }
        });
  }

  private final String historyResource;
  private final Class<?> workflowImplementation;

  public PromiseTimedGetCancellationReplayTest(
      String historyResource, Class<?> workflowImplementation) {
    this.historyResource = historyResource;
    this.workflowImplementation = workflowImplementation;
  }

  @Test
  public void replaysHistoryForFlagCombination() throws Exception {
    WorkflowReplayer.replayWorkflowExecutionFromResource(historyResource, workflowImplementation);
  }
}
