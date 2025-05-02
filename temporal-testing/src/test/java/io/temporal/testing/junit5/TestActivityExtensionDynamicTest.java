package io.temporal.testing.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.DynamicActivity;
import io.temporal.common.converter.EncodedValues;
import io.temporal.testing.TestActivityExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

public class TestActivityExtensionDynamicTest {

  @RegisterExtension
  public static final TestActivityExtension activityExtension =
      TestActivityExtension.newBuilder()
          .setActivityImplementations(new MyDynamicActivityImpl())
          .build();

  @ActivityInterface
  public interface MyActivity {

    @ActivityMethod(name = "OverriddenActivityMethod")
    String activity1(String input);
  }

  private static class MyDynamicActivityImpl implements DynamicActivity {

    @Override
    public Object execute(EncodedValues args) {
      return Activity.getExecutionContext().getInfo().getActivityType()
          + "-"
          + args.get(0, String.class);
    }
  }

  @Test
  public void extensionShouldResolveDynamicActivitiesParameters(MyActivity activity) {
    assertEquals("OverriddenActivityMethod-input1", activity.activity1("input1"));
  }
}
