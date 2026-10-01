package io.temporal.internal.sync;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.temporal.api.command.v1.ContinueAsNewWorkflowExecutionCommandAttributes;
import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.sdk.v1.UserMetadata;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.interceptors.Header;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor.ContinueAsNewInput;
import io.temporal.internal.common.SdkFlag;
import io.temporal.internal.common.SearchAttributesUtil;
import io.temporal.internal.replay.ReplayWorkflowContext;
import io.temporal.workflow.ContinueAsNewOptions;
import io.temporal.workflow.TimerOptions;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class SyncWorkflowContextTest {
  SyncWorkflowContext context;
  ReplayWorkflowContext mockReplayWorkflowContext = mock(ReplayWorkflowContext.class);

  @Before
  public void setUp() {
    this.context = DummySyncWorkflowContext.newDummySyncWorkflowContext();
    this.context.setReplayContext(mockReplayWorkflowContext);
  }

  @Test
  public void testUpsertSearchAttributes() {
    Map<String, Object> attr = new HashMap<>();
    attr.put("CustomKeywordField", "keyword");
    SearchAttributes serializedAttr = SearchAttributesUtil.encode(attr);

    context.upsertSearchAttributes(attr);
    verify(mockReplayWorkflowContext, times(1)).upsertSearchAttributes(serializedAttr);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testUpsertSearchAttributesException() {
    Map<String, Object> attr = new HashMap<>();
    context.upsertSearchAttributes(attr);
  }

  /**
   * Live workflow tests take the CANCEL_AWAIT_TIMER_ON_CONDITION branch, so this forces the branch
   * used without that flag through a mocked replay context.
   */
  @Test
  public void testAwaitPassesTimerSummaryWithoutCancelAwaitTimerFlag() {
    when(mockReplayWorkflowContext.tryUseSdkFlag(SdkFlag.CANCEL_AWAIT_TIMER_ON_CONDITION))
        .thenReturn(false);
    when(mockReplayWorkflowContext.getWorkflowType())
        .thenReturn(WorkflowType.newBuilder().setName("dummy-workflow").build());
    ExecutorService threadPool = Executors.newCachedThreadPool();
    Supplier<Boolean> neverTrue = () -> false;
    DeterministicRunner runner =
        DeterministicRunner.newRunner(
            threadPool::submit,
            context,
            () ->
                context.await(
                    Duration.ofHours(1),
                    TimerOptions.newBuilder().setSummary("await-summary").build(),
                    "await",
                    neverTrue));

    try {
      runner.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);

      ArgumentCaptor<UserMetadata> metadata = ArgumentCaptor.forClass(UserMetadata.class);
      verify(mockReplayWorkflowContext)
          .newTimer(eq(Duration.ofHours(1)), metadata.capture(), any());
      assertEquals(
          "await-summary",
          DefaultDataConverter.STANDARD_INSTANCE.fromPayload(
              metadata.getValue().getSummary(), String.class, String.class));
    } finally {
      runner.close();
      threadPool.shutdown();
    }
  }

  @Test
  public void testContinueAsNewBackoffStartInterval() {
    ExecutorService threadPool = Executors.newCachedThreadPool();

    Duration backoffStartInterval = Duration.ofSeconds(7);
    ContinueAsNewOptions options =
        ContinueAsNewOptions.newBuilder().setBackoffStartInterval(backoffStartInterval).build();
    DeterministicRunner runner =
        DeterministicRunner.newRunner(
            threadPool::submit,
            context,
            () ->
                context.continueAsNew(
                    new ContinueAsNewInput(null, options, new Object[0], Header.empty())));

    try {
      when(mockReplayWorkflowContext.getWorkflowType())
          .thenReturn(WorkflowType.newBuilder().setName("dummy-workflow").build());

      runner.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);

      ArgumentCaptor<ContinueAsNewWorkflowExecutionCommandAttributes> attributes =
          ArgumentCaptor.forClass(ContinueAsNewWorkflowExecutionCommandAttributes.class);
      verify(mockReplayWorkflowContext).continueAsNewOnCompletion(attributes.capture());
      assertEquals(7, attributes.getValue().getBackoffStartInterval().getSeconds());
    } finally {

      runner.close();
      threadPool.shutdown();
    }
  }
}
