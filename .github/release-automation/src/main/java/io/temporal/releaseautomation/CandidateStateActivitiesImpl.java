package io.temporal.releaseautomation;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CandidateStateActivitiesImpl implements CandidateStateActivities {
  private static final Gson GSON = new Gson();
  private final Path trustedRoot;
  private final Map<String, String> environment;

  public CandidateStateActivitiesImpl(Path trustedRoot, Map<String, String> workerEnvironment) {
    this.trustedRoot = trustedRoot;
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
    candidate.validate();
    String bucket = required("RELEASE_ARTIFACT_BUCKET");
    String key = "sdk-java/emergency/" + candidate.tag + ".json";
    List<String> listing =
        ProcessSupport.run(
            trustedRoot,
            Arrays.asList(
                "aws",
                "s3api",
                "list-objects-v2",
                "--bucket",
                bucket,
                "--prefix",
                key,
                "--output",
                "json"),
            environment);
    JsonObject listed = GSON.fromJson(String.join("\n", listing), JsonObject.class);
    if (listed == null || !listed.has("Contents")) {
      return false;
    }
    boolean exactKey =
        listed.getAsJsonArray("Contents").asList().stream()
            .map(element -> element.getAsJsonObject().get("Key").getAsString())
            .anyMatch(key::equals);
    if (!exactKey) {
      return false;
    }
    List<String> request =
        ProcessSupport.run(
            trustedRoot,
            Arrays.asList("aws", "s3", "cp", "s3://" + bucket + "/" + key, "-", "--no-progress"),
            environment);
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
