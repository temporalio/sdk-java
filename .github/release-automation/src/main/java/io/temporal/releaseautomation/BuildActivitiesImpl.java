package io.temporal.releaseautomation;

import io.temporal.activity.Activity;
import io.temporal.failure.ApplicationFailure;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class BuildActivitiesImpl implements BuildActivities {
  private final Path trustedRoot;
  private final Path sourceRoot;
  private final String trustedAutomationCommit;
  private final Consumer<Throwable> completion;

  public BuildActivitiesImpl(
      Path trustedRoot,
      Path sourceRoot,
      String trustedAutomationCommit,
      Consumer<Throwable> completion) {
    this.trustedRoot = trustedRoot;
    this.sourceRoot = sourceRoot;
    this.trustedAutomationCommit = trustedAutomationCommit;
    this.completion = completion;
  }

  @Override
  public ArtifactEntry buildAndStore(CandidateIdentity candidate, String platform) {
    try {
      ArtifactEntry artifact = build(candidate, platform);
      completion.accept(null);
      return artifact;
    } catch (Throwable failure) {
      completion.accept(failure);
      throw failure;
    }
  }

  private ArtifactEntry build(CandidateIdentity candidate, String platform) {
    candidate.validate();
    if (!candidate.trustedAutomationCommit.equals(trustedAutomationCommit)) {
      throw ApplicationFailure.newNonRetryableFailure(
          "Build Worker code does not match the frozen trusted commit.", "ReleaseIdentityConflict");
    }
    String expectedQueue = QueueNames.build(candidate, platform);
    if (!expectedQueue.equals(Activity.getExecutionContext().getInfo().getActivityTaskQueue())) {
      throw ApplicationFailure.newNonRetryableFailure(
          "Build Activity was routed to an unexpected Task Queue.", "ReleaseIdentityConflict");
    }
    Map<String, String> environment = new HashMap<>();
    environment.put("RELEASE_VERSION", candidate.version);
    environment.put("RELEASE_TAG", candidate.tag);
    environment.put("RELEASE_COMMIT", candidate.commitSha);
    environment.put("RELEASE_NOTES_FILE", candidate.releaseNotesPath);
    environment.put("RELEASE_NOTES_SHA256", candidate.releaseNotesSha256);
    environment.put("RELEASE_CANDIDATE_DIGEST", candidate.digest());
    environment.put("RELEASE_PLATFORM", platform);
    environment.put("TRUSTED_AUTOMATION_ROOT", trustedRoot.toString());
    try {
      List<String> output =
          ProcessSupport.run(
              sourceRoot,
              ProcessSupport.bash(
                  trustedRoot.resolve(
                      ".github/scripts/temporal-release/build-native-and-store.sh")),
              environment);
      if (output.size() != 1) {
        throw new IllegalStateException("Build command must emit exactly one manifest record.");
      }
      String[] fields = output.get(0).split("\\t", -1);
      if (fields.length != 4) {
        throw new IllegalStateException("Build command emitted an invalid manifest record.");
      }
      ArtifactEntry artifact =
          new ArtifactEntry(fields[0], fields[1], Long.parseLong(fields[2]), fields[3]);
      String expectedPrefix = "sdk-java/" + candidate.digest() + "/";
      if (!artifact.storageKey.startsWith(expectedPrefix)) {
        throw new IllegalStateException("Build command emitted an unexpected storage key.");
      }
      return artifact;
    } catch (ProcessSupport.CommandFailedException e) {
      if (e.getStatus() == 42) {
        throw ApplicationFailure.newNonRetryableFailure(
            "Durable artifact storage contains conflicting bytes.", "ReleaseIdentityConflict");
      }
      throw e;
    }
  }
}
