package io.temporal.releaseautomation;

import com.google.gson.Gson;
import io.temporal.activity.Activity;
import io.temporal.failure.ApplicationFailure;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class PublicationActivitiesImpl implements PublicationActivities {
  private final Path trustedRoot;
  private final Path sourceRoot;
  private final Map<String, String> environment;
  private final Consumer<Throwable> completion;

  public PublicationActivitiesImpl(
      Path trustedRoot,
      Path sourceRoot,
      Map<String, String> environment,
      Consumer<Throwable> completion) {
    this.trustedRoot = trustedRoot;
    this.sourceRoot = sourceRoot;
    this.environment = new HashMap<>(environment);
    this.completion = completion;
  }

  @Override
  public void preflight(PublicationInput input) {
    run(input, "preflight", Void.class);
  }

  @Override
  public MavenReceipt reconcileMaven(PublicationInput input) {
    return run(input, "maven", MavenReceipt.class);
  }

  @Override
  public String reconcileGithubDraft(PublicationInput input) {
    return run(input, "github-draft", String.class);
  }

  @Override
  public ReleaseResult publishGithubRelease(PublicationInput input, String mavenCentralUrl) {
    return run(input, "github-publish", ReleaseResult.class);
  }

  private <T> T run(PublicationInput input, String stage, Class<T> resultType) {
    try {
      T result = runCommand(input, stage, resultType);
      completion.accept(null);
      return result;
    } catch (Throwable failure) {
      completion.accept(failure);
      throw failure;
    }
  }

  private <T> T runCommand(PublicationInput input, String stage, Class<T> resultType) {
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
      commandEnvironment.put("RELEASE_STAGE", stage);
      commandEnvironment.put("EXPECTED_APPROVAL_ACTOR", input.approval.githubActor);
      commandEnvironment.put("TRUSTED_AUTOMATION_ROOT", trustedRoot.toString());
      copy(commandEnvironment, "GH_TOKEN");
      copy(commandEnvironment, "RELEASE_ARTIFACT_BUCKET");
      copy(commandEnvironment, "AWS_ACCESS_KEY_ID");
      copy(commandEnvironment, "AWS_SECRET_ACCESS_KEY");
      copy(commandEnvironment, "AWS_SESSION_TOKEN");
      copy(commandEnvironment, "AWS_REGION");
      copy(commandEnvironment, "AWS_DEFAULT_REGION");
      if ("maven".equals(stage) || "inspect".equals(stage)) {
        copy(commandEnvironment, "RH_USER");
        copy(commandEnvironment, "RH_PASSWORD");
      }
      if ("maven".equals(stage)) {
        copy(commandEnvironment, "JAR_SIGNING_KEY");
        copy(commandEnvironment, "JAR_SIGNING_KEY_ID");
        copy(commandEnvironment, "JAR_SIGNING_KEY_PASSWORD");
      }
      List<String> output =
          ProcessSupport.run(
              sourceRoot,
              ProcessSupport.bash(
                  trustedRoot.resolve(".github/scripts/temporal-release/reconcile-publication.sh")),
              commandEnvironment);
      if (!output.isEmpty()) {
        throw new IllegalStateException("Publication command wrote unexpected standard output.");
      }
      if (Void.class.equals(resultType)) {
        return null;
      }
      return new Gson()
          .fromJson(new String(Files.readAllBytes(outputFile), StandardCharsets.UTF_8), resultType);
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
      if (e.getStatus() == 44) {
        throw ApplicationFailure.newNonRetryableFailure(
            "A durable Maven intent has no discoverable Sonatype repository; an authenticated release manager must inspect Sonatype before authorizing another submission generation.",
            "MavenSubmissionAmbiguous");
      }
      throw e;
    } catch (IOException e) {
      throw new IllegalStateException("Unable to exchange publication state with the script.", e);
    } finally {
      delete(inputFile);
      delete(outputFile);
    }
  }

  private void copy(Map<String, String> commandEnvironment, String name) {
    String value = environment.get(name);
    if (value != null && !value.isEmpty()) {
      commandEnvironment.put(name, value);
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
