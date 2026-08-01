package io.temporal.releaseautomation;

import io.temporal.activity.ActivityInfo;
import java.util.Map;

final class PublicationGuard {
  private PublicationGuard() {}

  static void validate(PublicationInput input, ActivityInfo activity, Map<String, String> env) {
    input.release.validate();
    input.approval.validate();
    input.validatePolicy();
    requireEqual("Activity Workflow ID", input.workflowId, activity.getWorkflowId());
    requireEqual("Activity run ID", input.runId, activity.getWorkflowRunId());
    requireEqual("approval Workflow ID", input.workflowId, input.approval.workflowId);
    requireEqual("approval run ID", input.runId, input.approval.runId);
    requireEqual("release digest", input.release.digest(), input.approval.releaseDigest);
    requireEqual("repository", input.release.candidate.repository, input.approval.repository);
    requireEqual(
        "frozen trusted Worker commit",
        input.release.candidate.trustedAutomationCommit,
        input.approval.trustedWorkerCommit);
    requireEqual("expected Workflow ID", input.workflowId, required(env, "EXPECTED_WORKFLOW_ID"));
    requireEqual("expected run ID", input.runId, required(env, "EXPECTED_RUN_ID"));
    requireEqual(
        "expected repository",
        input.release.candidate.repository,
        required(env, "EXPECTED_REPOSITORY"));
    requireEqual("expected tag", input.release.candidate.tag, required(env, "EXPECTED_TAG"));
    requireEqual(
        "expected commit", input.release.candidate.commitSha, required(env, "EXPECTED_COMMIT_SHA"));
    requireEqual(
        "expected release notes hash",
        input.release.candidate.releaseNotesSha256,
        required(env, "EXPECTED_NOTES_SHA256"));
    requireEqual(
        "expected manifest hash",
        input.release.manifestSha256,
        required(env, "EXPECTED_MANIFEST_SHA256"));
    requireEqual(
        "expected release digest",
        input.release.digest(),
        required(env, "EXPECTED_RELEASE_DIGEST"));
    requireEqual(
        "expected approval run",
        Long.toString(input.approval.githubApprovalRunId),
        required(env, "EXPECTED_APPROVAL_RUN_ID"));
    requireEqual(
        "expected approval actor",
        input.approval.githubActor,
        required(env, "EXPECTED_APPROVAL_ACTOR"));
    requireEqual(
        "expected approval issue number",
        Long.toString(input.approval.githubIssueNumber),
        required(env, "EXPECTED_APPROVAL_ISSUE_NUMBER"));
    requireEqual(
        "expected approval issue node",
        input.approval.githubIssueNodeId,
        required(env, "EXPECTED_APPROVAL_ISSUE_NODE_ID"));
    requireEqual(
        "expected approval issue body hash",
        input.approval.githubIssueBodySha256,
        required(env, "EXPECTED_APPROVAL_ISSUE_BODY_SHA256"));
    requireEqual(
        "trusted Worker commit",
        input.approval.trustedWorkerCommit,
        required(env, "TRUSTED_WORKER_COMMIT"));
    requireEqual(
        "publication Task Queue",
        QueueNames.publication(input.release),
        activity.getActivityTaskQueue());
    requireEqual(
        "Maven submission generation",
        Integer.toString(input.mavenSubmissionGeneration),
        required(env, "EXPECTED_MAVEN_SUBMISSION_GENERATION"));
    if (input.mavenSubmissionGeneration > 0) {
      if (input.mavenRetryAuthorization == null) {
        throw new IllegalArgumentException("A Maven retry has no external authorization binding.");
      }
      input.mavenRetryAuthorization.validate();
      requireEqual(
          "Maven retry generation",
          Integer.toString(input.mavenSubmissionGeneration),
          Integer.toString(input.mavenRetryAuthorization.mavenSubmissionGeneration));
      requireEqual(
          "Maven retry authorization",
          input.mavenRetryAuthorization.authorizationSha256,
          required(env, "EXPECTED_MAVEN_RETRY_AUTHORIZATION_SHA256"));
    }
  }

  private static String required(Map<String, String> env, String name) {
    String value = env.get(name);
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException("Required publication expectation is missing: " + name);
    }
    return value;
  }

  private static void requireEqual(String field, String expected, String actual) {
    if (!expected.equals(actual)) {
      throw new IllegalArgumentException(field + " does not match the privileged Actions run.");
    }
  }
}
