package io.temporal.workflow.versionTests;

import io.temporal.internal.common.SdkFlag;
import io.temporal.internal.statemachines.WorkflowStateMachines;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public abstract class BaseVersionTest {

  @Parameterized.Parameter public static boolean setVersioningFlag;

  @Parameterized.Parameters()
  public static Object[] data() {
    return new Object[][] {{true}, {false}};
  }

  @Before
  public void setup() {
    if (setVersioningFlag) {
      WorkflowStateMachines.initialFlags =
          Collections.unmodifiableList(
              Arrays.asList(SdkFlag.SKIP_YIELD_ON_DEFAULT_VERSION, SdkFlag.SKIP_YIELD_ON_VERSION));
    }
  }
}
