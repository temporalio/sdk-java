package io.temporal.internal.activity;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.uber.m3.tally.NoopScope;
import com.uber.m3.tally.Scope;
import io.temporal.activity.ActivityInfo;
import io.temporal.activity.ManualActivityCompletionClient;
import io.temporal.client.WorkflowClient;
import io.temporal.common.converter.GlobalDataConverter;
import io.temporal.internal.client.external.ManualActivityCompletionClientFactory;
import io.temporal.payload.context.ActivitySerializationContext;
import io.temporal.payload.storage.StorageDriverActivityInfo;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.Test;

public class ActivityExecutionContextImplTest {

  @Test
  public void localManualCompletionIncludesActivityTarget() {
    WorkflowClient client = mock(WorkflowClient.class);
    when(client.getWorkflowServiceStubs()).thenReturn(mock(WorkflowServiceStubs.class));
    ActivityInfo info = mock(ActivityInfo.class);
    when(info.getNamespace()).thenReturn("test-namespace");
    when(info.getWorkflowId()).thenReturn(null);
    when(info.getWorkflowType()).thenReturn(null);
    when(info.getActivityId()).thenReturn("activity-id");
    when(info.getActivityRunId()).thenReturn("activity-run-id");
    when(info.getActivityType()).thenReturn("activity-type");
    when(info.getActivityTaskQueue()).thenReturn("task-queue");
    when(info.getTaskToken()).thenReturn(new byte[] {1, 2, 3});
    ManualActivityCompletionClientFactory completionClientFactory =
        mock(ManualActivityCompletionClientFactory.class);
    when(completionClientFactory.getClient(
            any(byte[].class),
            any(Scope.class),
            any(ActivitySerializationContext.class),
            any(StorageDriverTargetInfo.class)))
        .thenReturn(mock(ManualActivityCompletionClient.class));
    NoopScope metricsScope = new NoopScope();
    ActivityExecutionContextImpl context =
        new ActivityExecutionContextImpl(
            client,
            "test-namespace",
            new Object(),
            info,
            GlobalDataConverter.get(),
            mock(ScheduledExecutorService.class),
            completionClientFactory,
            () -> {},
            metricsScope,
            "test-identity",
            Duration.ofSeconds(60),
            Duration.ofSeconds(30),
            () -> {},
            null);

    context.useLocalManualCompletion();

    verify(completionClientFactory)
        .getClient(
            eq(new byte[] {1, 2, 3}),
            eq(metricsScope),
            any(ActivitySerializationContext.class),
            eq(
                new StorageDriverActivityInfo(
                    "test-namespace", "activity-id", "activity-run-id", "activity-type")));
  }
}
