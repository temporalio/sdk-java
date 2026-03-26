package io.temporal.internal.statemachines;

import static io.temporal.workflow.Workflow.DEFAULT_VERSION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import io.temporal.api.command.v1.Command;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.WorkflowTaskCompletedEventAttributes;
import io.temporal.api.sdk.v1.WorkflowTaskCompletedMetadata;
import io.temporal.internal.common.UpdateMessage;
import io.temporal.serviceclient.Version;
import java.util.List;
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

  @Test
  public void getVersionFalseCallbackDoesNotTriggerExtraEventLoop() {
    final int maxSupported = 7;

    class FalsePathListener implements StatesMachinesCallback {
      int eventLoopCalls;
      int versionCallbackCalls;
      Integer callbackVersion;
      Integer returnedVersion;

      @Override
      public void start(HistoryEvent startWorkflowEvent) {}

      @Override
      public void signal(HistoryEvent signalEvent) {}

      @Override
      public void update(UpdateMessage message) {}

      @Override
      public void cancel(HistoryEvent cancelEvent) {}

      @Override
      public void eventLoop() {
        eventLoopCalls++;
        if (eventLoopCalls > 1) {
          return;
        }

        returnedVersion =
            stateMachines.getVersion(
                "id1",
                DEFAULT_VERSION,
                maxSupported,
                (version, exception) -> {
                  versionCallbackCalls++;
                  callbackVersion = version;
                  assertNull(exception);
                  return false;
                });
        stateMachines.completeWorkflow(Optional.empty());
      }
    }

    FalsePathListener listener = new FalsePathListener();
    stateMachines = new WorkflowStateMachines(listener, m -> {});

    TestHistoryBuilder h =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask();

    List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1);

    assertEquals(1, listener.eventLoopCalls);
    assertEquals(1, listener.versionCallbackCalls);
    assertEquals(Integer.valueOf(maxSupported), listener.callbackVersion);
    assertEquals(Integer.valueOf(maxSupported), listener.returnedVersion);
    assertEquals(2, commands.size());
    assertEquals(CommandType.COMMAND_TYPE_RECORD_MARKER, commands.get(0).getCommandType());
    assertEquals(
        CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands.get(1).getCommandType());
  }
}
