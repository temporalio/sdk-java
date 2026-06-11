package io.temporal.internal.sync;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.temporal.api.command.v1.ContinueAsNewWorkflowExecutionCommandAttributes;
import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.common.interceptors.Header;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor.ContinueAsNewInput;
import io.temporal.internal.common.SearchAttributesUtil;
import io.temporal.internal.replay.ReplayWorkflowContext;
import io.temporal.workflow.ContinueAsNewOptions;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class SyncWorkflowContextTest {
  SyncWorkflowContext context;
  ReplayWorkflowContext mockReplayWorkflowContext = mock(ReplayWorkflowContext.class);
  ExecutorService threadPool;
  DeterministicRunner runner;

  @Before
  public void setUp() {
    this.threadPool = Executors.newCachedThreadPool();
    this.context = DummySyncWorkflowContext.newDummySyncWorkflowContext();
    this.context.setReplayContext(mockReplayWorkflowContext);
    when(mockReplayWorkflowContext.getWorkflowType())
        .thenReturn(WorkflowType.newBuilder().setName("dummy-workflow").build());
  }

  @After
  public void tearDown() {
    if (runner != null) {
      runner.close();
    }
    threadPool.shutdown();
  }

  @Test
  public void testUpsertSearchAttributes() {
    Map<String, Object> attr = new HashMap<>();
    attr.put("CustomKeywordField", "keyword");
    SearchAttributes serializedAttr = SearchAttributesUtil.encode(attr);

    context.upsertSearchAttributes(attr);
    verify(mockReplayWorkflowContext, times(1)).upsertSearchAttributes(serializedAttr);
  }

  @Test
  public void testContinueAsNewBackoffStartInterval() {
    Duration backoffStartInterval = Duration.ofSeconds(7);
    ContinueAsNewOptions options =
        ContinueAsNewOptions.newBuilder().setBackoffStartInterval(backoffStartInterval).build();
    runner =
        DeterministicRunner.newRunner(
            threadPool::submit,
            context,
            () ->
                context.continueAsNew(
                    new ContinueAsNewInput(null, options, new Object[0], Header.empty())));

    runner.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);

    ArgumentCaptor<ContinueAsNewWorkflowExecutionCommandAttributes> attributes =
        ArgumentCaptor.forClass(ContinueAsNewWorkflowExecutionCommandAttributes.class);
    verify(mockReplayWorkflowContext).continueAsNewOnCompletion(attributes.capture());
    assertEquals(7, attributes.getValue().getBackoffStartInterval().getSeconds());
  }

  @Test(expected = IllegalArgumentException.class)
  public void testUpsertSearchAttributesException() {
    Map<String, Object> attr = new HashMap<>();
    context.upsertSearchAttributes(attr);
  }
}
