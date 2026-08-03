package io.temporal.releaseautomation;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class CandidateStateActivitiesImpl implements CandidateStateActivities {
  private static final Gson GSON = new Gson();
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
  public boolean manualReleaseComplete(CandidateIdentity candidate) {
    started.run();
    try {
      boolean complete = readManualReleaseComplete(candidate);
      completion.accept(null);
      return complete;
    } catch (Throwable failure) {
      completion.accept(failure);
      throw failure;
    }
  }

  private boolean readManualReleaseComplete(CandidateIdentity candidate) {
    candidate.validate();
    required("RELEASE_ARTIFACT_BUCKET");
    environment.put("RELEASE_TAG", candidate.tag);
    List<String> request =
        ProcessSupport.run(
            trustedRoot,
            ProcessSupport.bash(
                trustedRoot.resolve(".github/scripts/temporal-release/read-emergency-state.sh")),
            environment);
    if (request.size() == 1 && "ABSENT".equals(request.get(0))) {
      return false;
    }
    JsonObject state = GSON.fromJson(String.join("\n", request), JsonObject.class);
    if (state == null || !state.has("candidate") || !state.has("state")) {
      throw new IllegalStateException("Durable emergency completion state is malformed.");
    }
    CandidateIdentity recorded = GSON.fromJson(state.get("candidate"), CandidateIdentity.class);
    recorded.validate();
    if (!candidate.digest().equals(recorded.digest())) {
      throw new IllegalStateException("Durable emergency state belongs to another candidate.");
    }
    return "COMPLETE".equals(state.get("state").getAsString());
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
