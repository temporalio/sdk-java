package io.temporal.common.metadata.testclasses;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface ActivityInterfaceWithOneNonAnnotatedMethod {
  boolean activityMethod();
}
