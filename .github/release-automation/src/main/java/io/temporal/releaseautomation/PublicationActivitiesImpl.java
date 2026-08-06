package io.temporal.releaseautomation;

import com.google.gson.Gson;
import io.temporal.activity.Activity;
import io.temporal.client.WorkflowClient;
import io.temporal.failure.ApplicationFailure;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class PublicationActivitiesImpl implements PublicationActivities {
  private final Path trustedRoot;
  private final Path sourceRoot;
  private final Map<String, String> environment;
  private final Consumer<Throwable> completion;
  private final Runnable started;
  private final WorkflowClient client;

  public PublicationActivitiesImpl(
      Path trustedRoot,
      Path sourceRoot,
      WorkflowClient client,
      Map<String, String> environment,
      Runnable started,
      Consumer<Throwable> completion) {
    this.trustedRoot = trustedRoot;
    this.sourceRoot = sourceRoot;
    this.client = client;
    this.environment = new HashMap<>(environment);
    this.started = started;
    this.completion = completion;
  }

  @Override
  public void preflight(PublicationInput input) {
    run(input, "preflight", Void.class);
  }

  @Override
  public String reconcileMavenRepository(PublicationInput input, boolean allowCreation) {
    return run(input, "maven-repository", String.class, allowCreation);
  }

  @Override
  public String reconcileMavenPortal(PublicationInput input) {
    return run(input, "maven-portal", String.class);
  }

  @Override
  public MavenReceipt publishMaven(PublicationInput input) {
    return run(input, "maven-publish", MavenReceipt.class);
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
    return run(input, stage, resultType, false);
  }

  private <T> T run(
      PublicationInput input, String stage, Class<T> resultType, boolean allowCreation) {
    started.run();
    try {
      T result = runCommand(input, stage, resultType, allowCreation);
      completion.accept(null);
      return result;
    } catch (Throwable failure) {
      completion.accept(failure);
      throw failure;
    }
  }

  private <T> T runCommand(
      PublicationInput input, String stage, Class<T> resultType, boolean allowCreation) {
    try {
      PublicationInput expected =
          new Gson()
              .fromJson(
                  new String(
                      Files.readAllBytes(requiredPath("RELEASE_EXPECTATION_FILE")),
                      StandardCharsets.UTF_8),
                  PublicationInput.class);
      PublicationGuard.validate(
          input,
          expected,
          Activity.getExecutionContext().getInfo(),
          required("TRUSTED_WORKER_COMMIT"));
      OwnershipStatus ownership =
          OwnershipActivitiesImpl.status(client, input.release.candidate.tag);
      if (ownership == null
          || !"TEMPORAL".equals(ownership.owner)
          || !input.release.candidate.commitSha.equals(ownership.commitSha)
          || !input.release.digest().equals(ownership.releaseDigest)) {
        throw new IllegalArgumentException(
            "Temporal does not own this exact tag, commit, and release identity.");
      }
    } catch (IOException e) {
      throw new IllegalStateException("Unable to read the privileged release expectation.", e);
    } catch (IllegalArgumentException e) {
      throw ApplicationFailure.newNonRetryableFailure(e.getMessage(), "InvalidApproval");
    }

    Path inputFile = null;
    Path outputFile = null;
    Path mavenArtifactsFile = null;
    try {
      inputFile = Files.createTempFile("temporal-release-input-", ".json");
      outputFile = Files.createTempFile("temporal-release-output-", ".json");
      mavenArtifactsFile = Files.createTempFile("temporal-release-maven-artifacts-", ".json");
      Files.write(inputFile, new Gson().toJson(input).getBytes(StandardCharsets.UTF_8));
      Files.write(
          mavenArtifactsFile,
          new Gson()
              .toJson(ReleasePolicy.mavenArtifacts(input.release.candidate.mavenPolicy))
              .getBytes(StandardCharsets.UTF_8));
      Map<String, String> commandEnvironment = new HashMap<>();
      commandEnvironment.put("RELEASE_INPUT_FILE", inputFile.toString());
      commandEnvironment.put("RELEASE_OUTPUT_FILE", outputFile.toString());
      commandEnvironment.put("RELEASE_MAVEN_ARTIFACTS_FILE", mavenArtifactsFile.toString());
      commandEnvironment.put("RELEASE_STAGE", stage);
      commandEnvironment.put(
          "RELEASE_ALLOW_MAVEN_REPOSITORY_CREATION", Boolean.toString(allowCreation));
      commandEnvironment.put("TRUSTED_AUTOMATION_ROOT", trustedRoot.toString());
      copy(commandEnvironment, "TRUSTED_WORKER_COMMIT");
      copy(commandEnvironment, "GH_TOKEN");
      if (stage.startsWith("maven-")) {
        copy(commandEnvironment, "RH_USER");
        copy(commandEnvironment, "RH_PASSWORD");
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
      if (e.getStatus() == 45) {
        throw ApplicationFailure.newNonRetryableFailure(
            "The exact Publisher Portal deployment failed validation.", "MavenDeploymentFailed");
      }
      if (e.getStatus() == 46) {
        throw ApplicationFailure.newNonRetryableFailure(
            "An exact GitHub Actions artifact expired or was deleted.", "ArtifactUnavailable");
      }
      throw e;
    } catch (IOException e) {
      throw new IllegalStateException("Unable to exchange publication state with the script.", e);
    } finally {
      delete(inputFile);
      delete(outputFile);
      delete(mavenArtifactsFile);
    }
  }

  private void copy(Map<String, String> commandEnvironment, String name) {
    String value = environment.get(name);
    if (value != null && !value.isEmpty()) {
      commandEnvironment.put(name, value);
    }
  }

  private String required(String name) {
    String value = environment.get(name);
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException("Required Worker value is missing: " + name);
    }
    return value;
  }

  private Path requiredPath(String name) {
    return Paths.get(required(name));
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
