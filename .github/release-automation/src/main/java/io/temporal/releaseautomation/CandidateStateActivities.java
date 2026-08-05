package io.temporal.releaseautomation;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface CandidateStateActivities {
  @ActivityMethod
  boolean manualReleaseOwns(CandidateIdentity candidate);
}
