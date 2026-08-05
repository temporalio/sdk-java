package io.temporal.releaseautomation;

import io.temporal.failure.ApplicationFailure;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class CandidateStateActivitiesImpl implements CandidateStateActivities {
  private final Path trustedRoot;
  private final Map<String, String> environment;
  private final Runnable started;
  private final Consumer<Throwable> completion;

  public CandidateStateActivitiesImpl(
      Path trustedRoot,
      Map<String, String> workerEnvironment,
      Runnable started,
      Consumer<Throwable> completion) {
    this.trustedRoot = trustedRoot;
    this.started = started;
    this.completion = completion;
    this.environment = new HashMap<>();
    copy(workerEnvironment, "AWS_ACCESS_KEY_ID");
    copy(workerEnvironment, "AWS_SECRET_ACCESS_KEY");
    copy(workerEnvironment, "AWS_SESSION_TOKEN");
    copy(workerEnvironment, "AWS_REGION");
    copy(workerEnvironment, "AWS_DEFAULT_REGION");
    copy(workerEnvironment, "RELEASE_ARTIFACT_BUCKET");
  }

  @Override
  public boolean manualReleaseOwns(CandidateIdentity candidate) {
    started.run();
    try {
      boolean owned = readManualReleaseOwnership(candidate);
      completion.accept(null);
      return owned;
    } catch (Throwable failure) {
      completion.accept(failure);
      throw failure;
    }
  }

  private boolean readManualReleaseOwnership(CandidateIdentity candidate) {
    candidate.validate();
    required("RELEASE_ARTIFACT_BUCKET");
    environment.put("RELEASE_TAG", candidate.tag);
    environment.put("RELEASE_COMMIT", candidate.commitSha);
    environment.put("RELEASE_OWNERSHIP_ACTION", "read");
    List<String> request;
    try {
      request =
          ProcessSupport.run(
              trustedRoot,
              ProcessSupport.bash(
                  trustedRoot.resolve(".github/scripts/temporal-release/manual-ownership.sh")),
              environment);
    } catch (ProcessSupport.CommandFailedException e) {
      if (e.getStatus() == 42) {
        throw ApplicationFailure.newNonRetryableFailure(
            "Durable tag ownership conflicts with the candidate.", "ReleaseIdentityConflict");
      }
      throw e;
    }
    if (request.size() != 1) {
      throw new IllegalStateException("Durable manual ownership output is malformed.");
    }
    if ("ABSENT".equals(request.get(0)) || "TEMPORAL".equals(request.get(0))) {
      return false;
    }
    if ("MANUAL".equals(request.get(0))) {
      return true;
    }
    throw new IllegalStateException("Durable manual ownership output is invalid.");
  }

  private void copy(Map<String, String> source, String name) {
    String value = source.get(name);
    if (value != null && !value.isEmpty()) {
      environment.put(name, value);
    }
  }

  private String required(String name) {
    String value = environment.get(name);
    if (value == null || value.isEmpty()) {
      throw new IllegalStateException("Candidate state Activity is missing " + name + ".");
    }
    return value;
  }
}
