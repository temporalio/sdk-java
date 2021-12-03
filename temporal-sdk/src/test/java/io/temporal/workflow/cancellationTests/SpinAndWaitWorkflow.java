package io.temporal.workflow.cancellationTests;

import static org.junit.Assert.fail;

import io.temporal.testUtils.Signal;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Instant;
import org.junit.Assert;

public class SpinAndWaitWorkflow {

  private static final Signal readyToBeCancelledSignal = new Signal();

  public static Signal getReadyToBeCancelledSignal() {
    return readyToBeCancelledSignal;
  }

  @WorkflowInterface
  public interface Interface {

    final int FailureReturnCode = -1;

    @SignalMethod
    void updateValue(int value);

    @WorkflowMethod
    int executeWorkflow();
  }

  public static class Implementation implements SpinAndWaitWorkflow.Interface {

    static final boolean IsPrintDebugActive = true;

    static final int Iterations = 5;
    static final int SleepSecs = 3;

    private int signaledValue = 0;
    private int countCanceledFailureExceptions = 0;

    private static String millisToString(long millis) {

      return Instant.ofEpochMilli(Workflow.currentTimeMillis()) + " (" + millis + ")";
    }

    private void printDebug(String message) {

      if (!IsPrintDebugActive) {
        return;
      }

      if (message == null) {
        message = "<null>";
      }

      System.out.println(
          "\nDEBUG: "
              + message
              + "\n    WorkflowTime:                   "
              + millisToString(Workflow.currentTimeMillis())
              + ";\n    RealTime:                       "
              + millisToString(System.currentTimeMillis())
              + ";\n    signaledValue:                  "
              + signaledValue
              + ";\n    countCanceledFailureExceptions: "
              + countCanceledFailureExceptions
              + ";");
    }

    @Override
    public void updateValue(int value) {

      printDebug(
          "updateValue(value="
              + value
              + ")"
              + "\n    WorkflowId:                      "
              + Workflow.getInfo().getWorkflowId()
              + ";"
              + "\n    RunId:                           "
              + Workflow.getInfo().getRunId()
              + ";");

      signaledValue = value;
    }

    @Override
    public int executeWorkflow() {

      try {
        printDebug(
            "executeWorkflow(): Started."
                + "\n    WorkflowId:                     "
                + Workflow.getInfo().getWorkflowId()
                + ";"
                + "\n    RunId:                          "
                + Workflow.getInfo().getRunId()
                + ";");

        for (int i = 0; i < Iterations; i++) {
          justSleep(i == 1);
          // sleepAndListenForSignal(i == 1);
        }

        printDebug(
            "executeWorkflow(): Exiting."
                + "\n    WorkflowId:      "
                + Workflow.getInfo().getWorkflowId()
                + ";"
                + "\n    RunId:           "
                + Workflow.getInfo().getRunId()
                + ";");

        return signaledValue;

      } catch (Throwable err) {

        printDebug(
            "executeWorkflow(): A throwable error escaped the workflow. Will exit."
                + "\n    Exception:                      "
                + err
                + ";\n    Stack Trace:                    see right BELOW;");
        err.printStackTrace();
        return FailureReturnCode;
      }
    }

    private void sleepAndListenForSignal(boolean signalReadyToBeCancelled) {

      // Workflow.await(Duration.ofSeconds(SleepSecs), () -> (signaledValue != 0));
    }

    private void justSleep(boolean signalReadyToBeCancelled) {

      try {

        printDebug("justSleep(): Starting Sleep.");

        if (signalReadyToBeCancelled) {
          getReadyToBeCancelledSignal().signal();
        }

        Workflow.sleep(SleepSecs * 1000);

        printDebug("justSleep(): Ended Sleep.");

      } catch (Exception ex) {

        if (ex instanceof io.temporal.failure.CanceledFailure) {
          countCanceledFailureExceptions++;
        }

        printDebug(
            "justSleep(): Exception during sleep."
                + "\n    Exception:                      "
                + ex
                + ";\n    Stack Trace:                    see right BELOW;");
        ex.printStackTrace();

        Assert.assertEquals(
            "countCanceledFailureExceptions must not grow above 1.",
            1,
            countCanceledFailureExceptions);

        if (!(ex instanceof io.temporal.failure.CanceledFailure)) {
          fail("Unexpected exception (" + ex.getClass().getName() + "): " + ex.getMessage());
        }
      }
    }
  }
}
