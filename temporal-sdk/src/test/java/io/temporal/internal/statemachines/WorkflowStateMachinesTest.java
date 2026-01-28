package io.temporal.internal.statemachines;

import static org.junit.Assert.assertEquals;

import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.WorkflowTaskCompletedEventAttributes;
import io.temporal.api.sdk.v1.WorkflowTaskCompletedMetadata;
import io.temporal.serviceclient.Version;
import java.util.Optional;
import org.junit.Test;

public class WorkflowStateMachinesTest {
  private WorkflowStateMachines stateMachines;

  private WorkflowStateMachines newStateMachines(TestEntityManagerListenerBase listener) {
    return new WorkflowStateMachines(listener, m -> {});
  }

  private class TestActivityListener extends TestEntityManagerListenerBase {
    @Override
    public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
      builder.add(v -> stateMachines.completeWorkflow(Optional.empty()));
    }
  }

  private void sdkNameAndVersionTest(
      String inputSdkVersion,
      String inputSdkName,
      String expectedSdkName,
      String expectedSdkVersion) {
    TestHistoryBuilder h = new TestHistoryBuilder();
    TestEntityManagerListenerBase listener = new TestActivityListener();
    stateMachines = newStateMachines(listener);

    h.add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED);
    h.addWorkflowTaskScheduledAndStarted();
    h.add(
        EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED,
        WorkflowTaskCompletedEventAttributes.newBuilder()
            .setScheduledEventId(h.getWorkflowTaskScheduledEventId())
            .setSdkMetadata(
                WorkflowTaskCompletedMetadata.newBuilder()
                    .setSdkVersion(inputSdkVersion)
                    .setSdkName(inputSdkName)));
    h.addWorkflowTaskScheduledAndStarted();
    assertEquals(2, h.getWorkflowTaskCount());

    h.handleWorkflowTaskTakeCommands(stateMachines, 2);

    assertEquals(expectedSdkName, stateMachines.sdkNameToWrite());
    assertEquals(expectedSdkVersion, stateMachines.sdkVersionToWrite());
  }

  @Test
  public void testWritesSdkNameAndVersionWhenDifferent() {
    sdkNameAndVersionTest("hi", "skflajk", Version.SDK_NAME, Version.LIBRARY_VERSION);
  }

  @Test
  public void doesNotWriteSdkNameAndVersionWhenSame() {
    sdkNameAndVersionTest(Version.LIBRARY_VERSION, Version.SDK_NAME, null, null);
  }

  @Test
  public void writesOnlyNameIfChanged() {
    sdkNameAndVersionTest(Version.LIBRARY_VERSION, "sakflasjklf", Version.SDK_NAME, null);
  }

  @Test
  public void writesOnlyVersionIfChanged() {
    sdkNameAndVersionTest("safklasjf", Version.SDK_NAME, null, Version.LIBRARY_VERSION);
  }
}
