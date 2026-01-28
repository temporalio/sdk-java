package io.temporal.testing.junit5;

import static org.junit.jupiter.api.Assertions.*;

import com.uber.m3.tally.NoopScope;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.testing.TestActivityEnvironment;
import io.temporal.testing.TestActivityExtension;
import io.temporal.testing.TestEnvironmentOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

public class TestActivityExtensionTest {

  public static class CustomMetricsScope extends NoopScope {}

  @RegisterExtension
  public static final TestActivityExtension activityExtension =
      TestActivityExtension.newBuilder()
          .setTestEnvironmentOptions(
              TestEnvironmentOptions.newBuilder().setMetricsScope(new CustomMetricsScope()).build())
          .setActivityImplementations(new MyActivityImpl())
          .build();

  @ActivityInterface
  public interface MyActivity {
    @ActivityMethod(name = "OverriddenActivityMethod")
    String activity1(String input);
  }

  private static class MyActivityImpl implements MyActivity {
    @Override
    public String activity1(String input) {
      assertInstanceOf(
          CustomMetricsScope.class,
          Activity.getExecutionContext().getMetricsScope(),
          "The custom metrics scope should be available for the activity");
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
