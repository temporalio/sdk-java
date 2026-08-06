package io.temporal.releaseautomation;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface PublicationActivities {
  @ActivityMethod
  void preflight(PublicationInput input);

  @ActivityMethod
  String reconcileMavenRepository(PublicationInput input, boolean allowCreation);

  @ActivityMethod
  String reconcileMavenPortal(PublicationInput input);

  @ActivityMethod
  MavenReceipt publishMaven(PublicationInput input);

  @ActivityMethod
  String reconcileGithubDraft(PublicationInput input);

  @ActivityMethod
  ReleaseResult publishGithubRelease(PublicationInput input, String mavenCentralUrl);
}
