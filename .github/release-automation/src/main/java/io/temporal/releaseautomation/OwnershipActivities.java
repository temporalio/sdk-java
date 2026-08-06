package io.temporal.releaseautomation;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface OwnershipActivities {
  @ActivityMethod
  OwnershipStatus claimTemporal(ReleaseIdentity release);

  @ActivityMethod
  OwnershipStatus handoffManual(ReleaseIdentity release, ControlEvidence evidence);
}
