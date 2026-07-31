package io.temporal.releaseautomation;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface BuildActivities {
  @ActivityMethod
  ArtifactEntry buildAndStore(CandidateIdentity candidate, String platform);
}
