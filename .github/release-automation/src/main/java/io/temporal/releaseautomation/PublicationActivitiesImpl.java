package io.temporal.releaseautomation;

import com.google.gson.Gson;
import io.temporal.activity.Activity;
import io.temporal.failure.ApplicationFailure;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PublicationActivitiesImpl implements PublicationActivities {
  private final Path trustedRoot;
  private final Path sourceRoot;
  private final Map<String, String> environment;

  public PublicationActivitiesImpl(
      Path trustedRoot, Path sourceRoot, Map<String, String> environment) {
    this.trustedRoot = trustedRoot;
    this.sourceRoot = sourceRoot;
    this.environment = new HashMap<>(environment);
  }

  @Override
  public ReleaseResult reconcileAndPublish(PublicationInput input) {
    try {
      PublicationGuard.validate(input, Activity.getExecutionContext().getInfo(), environment);
    } catch (IllegalArgumentException e) {
      throw ApplicationFailure.newNonRetryableFailure(e.getMessage(), "InvalidApproval");
    }

    Path inputFile = null;
    Path outputFile = null;
    try {
      inputFile = Files.createTempFile("temporal-release-input-", ".json");
      outputFile = Files.createTempFile("temporal-release-output-", ".json");
      Files.write(inputFile, new Gson().toJson(input).getBytes(StandardCharsets.UTF_8));
      Map<String, String> commandEnvironment = new HashMap<>();
      commandEnvironment.put("RELEASE_INPUT_FILE", inputFile.toString());
      commandEnvironment.put("RELEASE_OUTPUT_FILE", outputFile.toString());
      commandEnvironment.put("EXPECTED_APPROVAL_ACTOR", input.approval.githubActor);
      List<String> output =
          ProcessSupport.run(
              sourceRoot,
              Arrays.asList(
                  trustedRoot
                      .resolve(".github/scripts/temporal-release/reconcile-publication.sh")
                      .toString()),
              commandEnvironment);
      if (!output.isEmpty()) {
        throw new IllegalStateException("Publication command wrote unexpected standard output.");
      }
      return new Gson()
          .fromJson(
              new String(Files.readAllBytes(outputFile), StandardCharsets.UTF_8),
              ReleaseResult.class);
    } catch (ProcessSupport.CommandFailedException e) {
      if (e.getStatus() == 42) {
        throw ApplicationFailure.newNonRetryableFailure(
            "An immutable external release identity or checksum conflicts.",
            "ReleaseIdentityConflict");
      }
      if (e.getStatus() == 43) {
        throw ApplicationFailure.newNonRetryableFailure(
            "GitHub approval evidence is invalid.", "InvalidApproval");
      }
      throw e;
    } catch (IOException e) {
      throw new IllegalStateException("Unable to exchange publication state with the script.", e);
    } finally {
      delete(inputFile);
      delete(outputFile);
    }
  }

  private static void delete(Path path) {
    if (path != null) {
      try {
        Files.deleteIfExists(path);
      } catch (IOException ignored) {
        // The runner is ephemeral and this file contains identities, not credentials.
      }
    }
  }
}
