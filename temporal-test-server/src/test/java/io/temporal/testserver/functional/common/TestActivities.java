package io.temporal.testserver.functional.common;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

public class TestActivities {
  @ActivityInterface
  public interface ActivityReturnsString {
    @ActivityMethod
    String execute();
  }
}
