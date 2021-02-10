package io.temporal.testing;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

public class TestActivityExtensionTest {

  @RegisterExtension
  public static final TestActivityExtension activityExtension =
      TestActivityExtension.newBuilder().setActivityImplementations(new MyActivityImpl()).build();

  @ActivityInterface
  public interface MyActivity {
    @ActivityMethod(name = "OverriddenActivityMethod")
    String activity1(String input);
  }

  private static class MyActivityImpl implements MyActivity {
    @Override
    public String activity1(String input) {
      return Activity.getExecutionContext().getInfo().getActivityType() + "-" + input;
    }
  }

  @Test
  public void extensionShouldLaunchTestEnvironmentAndResolveParameters(
      TestActivityEnvironment testEnv, MyActivity activity) {
    assertAll(
        () -> assertNotNull(testEnv),
        () -> assertEquals("OverriddenActivityMethod-input1", activity.activity1("input1")));
  }
}
