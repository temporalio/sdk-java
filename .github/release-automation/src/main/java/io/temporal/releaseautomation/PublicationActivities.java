package io.temporal.releaseautomation;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface PublicationActivities {
  @ActivityMethod
  void preflight(PublicationInput input);

  @ActivityMethod
  MavenReceipt reconcileMaven(PublicationInput input);

  @ActivityMethod
  String reconcileGithubDraft(PublicationInput input);

  @ActivityMethod
  ReleaseResult publishGithubRelease(PublicationInput input, String mavenCentralUrl);
}
