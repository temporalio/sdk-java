package io.temporal.workflow.versionTests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.PreferredVersionProviderInput;
import io.temporal.worker.VersionPreference;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflowReturnString;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class PreferredVersionProviderTest {
  private static final String CHANGE_ID = "preferred-change";
  private static final AtomicInteger providerCalls = new AtomicInteger();
  private static final AtomicReference<PreferredVersionProviderInput> providerInput =
      new AtomicReference<>();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestPreferredVersionWorkflow.class)
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setStickyQueueScheduleToStartTimeout(Duration.ZERO)
                  .setPreferredVersionProvider(
                      (input) -> {
                        providerCalls.incrementAndGet();
                        providerInput.set(input);
                        return Optional.of(VersionPreference.of(Workflow.DEFAULT_VERSION));
                      })
                  .build())
          .build();

  @Before
  public void setUp() {
    providerCalls.set(0);
    providerInput.set(null);
  }

  @Test
  public void providerReceivesGetVersionInputAndIsNotCalledOnReplay() {
    TestWorkflowReturnString workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflowReturnString.class);

    String result = workflowStub.execute();

    assertEquals("old", result);
    assertEquals(1, providerCalls.get());

    PreferredVersionProviderInput input = providerInput.get();
    assertNotNull(input);
    assertEquals(CHANGE_ID, input.getChangeId());
    assertEquals(Workflow.DEFAULT_VERSION, input.getMinSupported());
    assertEquals(1, input.getMaxSupported());
    assertEquals(
        WorkflowStub.fromTyped(workflowStub).getExecution().getWorkflowId(),
        input.getWorkflowInfo().getWorkflowId());
  }

  public static class TestPreferredVersionWorkflow implements TestWorkflowReturnString {
    @Override
    public String execute() {
      int version = Workflow.getVersion(CHANGE_ID, Workflow.DEFAULT_VERSION, 1);
      Workflow.sleep(Duration.ofMillis(1));
      assertEquals(version, Workflow.getVersion(CHANGE_ID, Workflow.DEFAULT_VERSION, 1));
      return version == Workflow.DEFAULT_VERSION ? "old" : "new";
    }
  }
}
