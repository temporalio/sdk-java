package io.temporal.workflow.versionTests;

import io.temporal.internal.common.SdkFlag;
import io.temporal.internal.statemachines.WorkflowStateMachines;
import io.temporal.worker.WorkflowImplementationOptions;
import java.util.Arrays;
import java.util.Collections;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public abstract class BaseVersionTest {

  public static boolean setVersioningFlag;

  public static boolean upsertVersioningSA = false;

  @Parameterized.Parameters()
  public static Object[] data() {
    return new Object[][] {{true, true}, {false, true}, {true, false}, {false, false}};
  }

  public WorkflowImplementationOptions options;

  public WorkflowImplementationOptions getDefaultWorkflowImplementationOptions() {
    return WorkflowImplementationOptions.newBuilder()
        .setEnableUpsertVersionSearchAttributes(upsertVersioningSA)
        .build();
  }

  public BaseVersionTest(boolean setVersioningFlag, boolean upsertVersioningSA) {
    if (setVersioningFlag) {
      WorkflowStateMachines.initialFlags =
          Collections.unmodifiableList(
              Arrays.asList(SdkFlag.SKIP_YIELD_ON_DEFAULT_VERSION, SdkFlag.SKIP_YIELD_ON_VERSION));
    }
    this.setVersioningFlag = setVersioningFlag;
    this.upsertVersioningSA = upsertVersioningSA;
  }
}
