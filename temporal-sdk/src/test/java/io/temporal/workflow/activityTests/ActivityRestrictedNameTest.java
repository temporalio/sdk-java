package io.temporal.workflow.activityTests;

import io.temporal.activity.*;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ActivityRestrictedNameTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setDoNotStart(true).build();

  @Test
  public void testRegisteringRestrictedActivity() {
    IllegalArgumentException e =
        Assert.assertThrows(
            IllegalArgumentException.class,
            () ->
                testWorkflowRule
                    .getWorker()
                    .registerActivitiesImplementations(new ActivityWithRestrictedNamesImpl()));
    Assert.assertEquals(
        "Activity name \"__temporal_activity\" must not start with \"__temporal_\"",
        e.getMessage());

    e =
        Assert.assertThrows(
            IllegalArgumentException.class,
            () ->
                testWorkflowRule
                    .getWorker()
                    .registerActivitiesImplementations(
                        new ActivityWithRestrictedOverrideNameImpl()));
    Assert.assertEquals(
        "Activity name \"__temporal_activity\" must not start with \"__temporal_\"",
        e.getMessage());
  }

  @ActivityInterface
  public interface ActivityWithRestrictedOverrideName {

    @ActivityMethod(name = "__temporal_activity")
    String temporalActivity(String workflowId);
  }

  public static class ActivityWithRestrictedOverrideNameImpl
      implements ActivityWithRestrictedOverrideName {
    @Override
    public String temporalActivity(String workflowId) {
      return null;
    }
  }

  @ActivityInterface
  public interface ActivityWithRestrictedNames {
    String __temporal_activity(String workflowId);
  }

  public static class ActivityWithRestrictedNamesImpl implements ActivityWithRestrictedNames {
    @Override
    public String __temporal_activity(String workflowId) {
      return null;
    }
  }
}
