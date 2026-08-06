package io.temporal.releaseautomation;

import com.google.gson.Gson;
import io.temporal.activity.ActivityInfo;
import java.util.HashSet;
import java.util.Set;

final class PublicationGuard {
  private static final Gson GSON = new Gson();

  private PublicationGuard() {}

  static void validate(
      PublicationInput input,
      PublicationInput expected,
      ActivityInfo activity,
      String trustedWorkerCommit) {
    validateInput(input);
    validateInput(expected);
    requireEqual("privileged publication input", GSON.toJson(expected), GSON.toJson(input));
    requireEqual("Activity Workflow ID", input.workflowId, activity.getWorkflowId());
    requireEqual("Activity run ID", input.runId, activity.getWorkflowRunId());
    requireEqual("approval Workflow ID", input.workflowId, input.approval.workflowId);
    requireEqual("approval run ID", input.runId, input.approval.runId);
    requireEqual("release digest", input.release.digest(), input.approval.releaseDigest);
    requireEqual(
        "frozen trusted Worker commit",
        input.release.candidate.trustedAutomationCommit,
        input.approval.trustedWorkerCommit);
    requireEqual("trusted Worker commit", input.approval.trustedWorkerCommit, trustedWorkerCommit);
    requireEqual(
        "publication Task Queue",
        QueueNames.publication(input.release, input.mavenSubmissionGeneration),
        activity.getActivityTaskQueue());
  }

  private static void validateInput(PublicationInput input) {
    if (input == null
        || input.release == null
        || input.approvalRequest == null
        || input.approval == null) {
      throw new IllegalArgumentException("Publication input is incomplete.");
    }
    input.release.validate();
    input.approvalRequest.validate();
    input.approval.validate();
    if (!input.approvalRequest.matches(input.approval)) {
      throw new IllegalArgumentException("Approval does not match its durable request.");
    }
    if (input.mavenSubmissionGeneration < 0) {
      throw new IllegalArgumentException("Maven submission generation is invalid.");
    }
    if (input.mavenSubmissionGeneration == 0 && input.mavenRetryAuthorization != null) {
      throw new IllegalArgumentException("Initial Maven submission has retry authorization.");
    }
    if (input.mavenSubmissionGeneration > 0) {
      if (input.mavenRetryAuthorization == null) {
        throw new IllegalArgumentException("A Maven retry has no external authorization binding.");
      }
      input.mavenRetryAuthorization.validate();
      if (input.mavenRetryAuthorization.mavenSubmissionGeneration
          != input.mavenSubmissionGeneration) {
        throw new IllegalArgumentException("Maven retry generation does not match.");
      }
    }
    if (input.mavenPayload == null) {
      throw new IllegalArgumentException("The frozen Maven payload is missing.");
    }
    input.mavenPayload.validate();
    if (!ReleasePolicy.githubMavenArtifactName(input.release)
            .equals(input.mavenPayload.artifactName)
        || input.mavenPayload.files.size() != 1
        || !"maven-payload.tar".equals(input.mavenPayload.files.get(0).name)) {
      throw new IllegalArgumentException("The frozen Maven payload identity is invalid.");
    }
    if (input.mavenGenerations == null) {
      throw new IllegalArgumentException("Maven generation state is missing.");
    }
    Set<Integer> generations = new HashSet<>();
    for (MavenGenerationState generation : input.mavenGenerations) {
      generation.validate(input.release.digest());
      if (!generations.add(generation.generation)) {
        throw new IllegalArgumentException("Maven generation state is duplicated.");
      }
    }
  }

  private static void requireEqual(String field, String expected, String actual) {
    if (expected == null || !expected.equals(actual)) {
      throw new IllegalArgumentException(field + " does not match the privileged Actions run.");
    }
  }
}
