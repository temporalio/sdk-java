package io.temporal.releaseautomation;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionMetadata;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowTargetOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class ReleaseAutomationMain {
  private static final Gson GSON = new Gson();
  private static final Path REPOSITORY_ROOT = repositoryRoot();

  private ReleaseAutomationMain() {}

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      throw new IllegalArgumentException(
          "Expected candidate-outputs, maven-policy, emergency-input, start-candidate, discover, approval-target, approval-request, approve, control, inspect, inspect-if-present, publication-input, or worker.");
    }
    Map<String, String> environment = System.getenv();
    if ("candidate-outputs".equals(args[0])) {
      requireArguments(args, 2);
      candidateOutputs(read(args[1], CandidateIdentity.class));
      return;
    }
    if ("emergency-input".equals(args[0])) {
      requireArguments(args, 3);
      emergencyInput(read(args[1], CandidateIdentity.class), Paths.get(args[2]), environment);
      return;
    }
    if ("maven-policy".equals(args[0])) {
      requireArguments(args, 2);
      mavenPolicy(Paths.get(args[1]));
      return;
    }
    try (TemporalConnection temporal = TemporalConnection.fromEnvironment(environment)) {
      switch (args[0]) {
        case "start-candidate":
          requireArguments(args, 2);
          startCandidate(temporal.client, read(args[1], CandidateIdentity.class));
          return;
        case "discover":
          requireArguments(args, 2);
          discover(temporal.client, args[1], environment);
          return;
        case "approve":
          requireArguments(args, 1);
          approve(temporal.client, environment);
          return;
        case "approval-request":
          requireArguments(args, 1);
          requestApproval(temporal.client, environment);
          return;
        case "approval-target":
          requireArguments(args, 1);
          approvalTarget(temporal.client, environment);
          return;
        case "control":
          requireArguments(args, 4);
          control(temporal.client, environment, args[1], args[2], args[3]);
          return;
        case "inspect":
          requireArguments(args, 3);
          inspect(temporal.client, args[1], args[2]);
          return;
        case "inspect-if-present":
          requireArguments(args, 3);
          inspectIfPresent(temporal.client, args[1], args[2]);
          return;
        case "publication-input":
          requireArguments(args, 4);
          publicationInput(temporal.client, args[1], args[2], Paths.get(args[3]));
          return;
        case "worker":
          requireArguments(args, 3);
          runWorker(temporal.client, args[1], args[2], environment);
          return;
        default:
          throw new IllegalArgumentException("Unknown command: " + args[0]);
      }
    }
  }

  private static void candidateOutputs(CandidateIdentity candidate) {
    candidate.validate();
    writeOutput("candidate_digest", candidate.digest());
    writeOutput("automation_commit", candidate.trustedAutomationCommit);
    writeOutput("commit_sha", candidate.commitSha);
    writeOutput("notes_sha256", candidate.releaseNotesSha256);
    writeOutput("tag", candidate.tag);
    writeOutput("version", candidate.version);
    writeOutput("maven_policy", candidate.mavenPolicy);
  }

  private static void mavenPolicy(Path settingsFile) throws IOException {
    Pattern include = Pattern.compile("^include ['\"]([^'\"]+)['\"]$");
    List<String> projects = new ArrayList<>();
    for (String line : Files.readAllLines(settingsFile, StandardCharsets.UTF_8)) {
      Matcher match = include.matcher(line);
      if (match.matches()) {
        projects.add(match.group(1));
      }
    }
    String policy = ReleasePolicy.mavenPolicyForProjects(projects);
    writeOutput("maven_policy", policy);
    writeOutput("maven_artifacts_json", GSON.toJson(ReleasePolicy.mavenArtifacts(policy)));
  }

  private static void emergencyInput(
      CandidateIdentity candidate, Path manifestPath, Map<String, String> env) throws IOException {
    candidate.validate();
    String actor = required(env, "EMERGENCY_APPROVAL_ACTOR");
    verifyApprover(actor);
    long githubRunId = Long.parseLong(required(env, "EMERGENCY_APPROVAL_RUN_ID"));
    List<ArtifactEntry> artifacts = new ArrayList<>();
    for (String line : Files.readAllLines(manifestPath, StandardCharsets.UTF_8)) {
      String[] fields = line.split("\\t", -1);
      if (fields.length != 4) {
        throw new IllegalArgumentException("Emergency artifact manifest record is invalid.");
      }
      artifacts.add(new ArtifactEntry(fields[0], fields[1], Long.parseLong(fields[2]), fields[3]));
    }
    ReleaseIdentity identity = new ReleaseIdentity(candidate, new ArtifactManifest(artifacts));
    String workflowId = QueueNames.releaseWorkflowId(identity);
    String runId = identity.digest().substring(0, 32);
    ApprovalEvidence authorization =
        new ApprovalEvidence(
            ReleasePolicy.REPOSITORY,
            identity.digest(),
            workflowId,
            runId,
            githubRunId,
            actor,
            githubRunId,
            "EMERGENCY_" + githubRunId,
            Digests.sha256("emergency-handoff\n" + identity.digest()),
            candidate.trustedAutomationCommit);
    PublicationInput input = new PublicationInput(identity, authorization, workflowId, runId);
    String submissionGeneration = env.get("MAVEN_SUBMISSION_GENERATION");
    if (submissionGeneration != null && !submissionGeneration.isEmpty()) {
      input.mavenSubmissionGeneration = Integer.parseInt(submissionGeneration);
      if (input.mavenSubmissionGeneration < 0) {
        throw new IllegalArgumentException("Maven submission generation cannot be negative.");
      }
      if (input.mavenSubmissionGeneration > 0) {
        ControlEvidence retry = new ControlEvidence();
        retry.action = "retry-maven-submission";
        retry.repository = ReleasePolicy.REPOSITORY;
        retry.releaseDigest = identity.digest();
        retry.workflowId = workflowId;
        retry.runId = runId;
        retry.githubRunId = Long.parseLong(required(env, "MAVEN_RETRY_AUTHORIZATION_RUN_ID"));
        retry.githubActor = required(env, "MAVEN_RETRY_AUTHORIZATION_ACTOR");
        retry.tag = candidate.tag;
        retry.commitSha = candidate.commitSha;
        retry.reason = "Release manager authorized one inspected emergency Maven generation.";
        retry.mavenSubmissionGeneration = input.mavenSubmissionGeneration;
        retry.authorizationSha256 = required(env, "MAVEN_RETRY_AUTHORIZATION_SHA256");
        retry.validate();
        input.mavenRetryAuthorization = retry;
      }
    }
    input.emergencyHandoff = true;
    input.handoff =
        new ControlEvidence(
            "handoff-manual",
            ReleasePolicy.REPOSITORY,
            identity.digest(),
            workflowId,
            runId,
            githubRunId,
            actor,
            candidate.tag,
            candidate.commitSha,
            "Release manager selected the independently durable emergency path.");
    input.handoff.recordedAtMillis = System.currentTimeMillis();
    Path export = Paths.get(required(env, "RUNNER_TEMP")).resolve("sdk-java-emergency-input.json");
    Files.write(export, GSON.toJson(input).getBytes(StandardCharsets.UTF_8));
    writeOutput("release_input_file", export.toString());
    writeOutput("workflow_id", workflowId);
    writeOutput("run_id", runId);
    writeOutput("release_digest", identity.digest());
    writeOutput("manifest_sha256", identity.manifestSha256);
    writeOutput("notes_sha256", candidate.releaseNotesSha256);
    writeOutput("automation_commit", candidate.trustedAutomationCommit);
  }

  private static void startCandidate(WorkflowClient client, CandidateIdentity candidate) {
    candidate.validate();
    CandidateWorkflow workflow =
        client.newWorkflowStub(
            CandidateWorkflow.class,
            WorkflowOptions.newBuilder()
                .setWorkflowId(QueueNames.candidateWorkflowId(candidate))
                .setTaskQueue(QueueNames.candidateWorkflow(candidate))
                .setWorkflowIdReusePolicy(
                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .setWorkflowIdConflictPolicy(
                    WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
                .setMemo(Collections.singletonMap("CandidateIdentity", candidate))
                .build());
    WorkflowExecution execution = WorkflowClient.start(workflow::prepare, candidate);
    writeOutput("workflow_id", execution.getWorkflowId());
    writeOutput("run_id", execution.getRunId());
    writeOutput("candidate_digest", candidate.digest());
    writeOutput("task_queue", QueueNames.candidateWorkflow(candidate));
  }

  private static void discover(
      WorkflowClient client, String scope, Map<String, String> environment) {
    String trustedAutomationCommits =
        required(environment, "RELEASE_AUTOMATION_REF")
            + ","
            + optional(environment, "RELEASE_AUTOMATION_COMPATIBLE_REFS", "");
    for (String commit : trustedAutomationCommits.split(",")) {
      if (!commit.isEmpty() && !commit.matches("[0-9a-f]{40}")) {
        throw new IllegalArgumentException(
            "Trusted release-automation refs must be full commit SHAs.");
      }
    }
    List<DiscoveryJob> jobs;
    if ("unprivileged".equals(scope)) {
      jobs = discoverUnprivileged(client, trustedAutomationCommits);
    } else if ("publication".equals(scope)) {
      jobs = discoverPublication(client, trustedAutomationCommits);
    } else if ("approvals".equals(scope)) {
      jobs = discoverApprovals(client, trustedAutomationCommits);
    } else {
      throw new IllegalArgumentException(
          "Discovery scope must be unprivileged, publication, or approvals.");
    }
    JsonObject matrix = new JsonObject();
    matrix.add("include", GSON.toJsonTree(jobs));
    writeOutput("matrix", GSON.toJson(matrix));
    writeOutput("count", Integer.toString(jobs.size()));
  }

  private static List<DiscoveryJob> discoverUnprivileged(
      WorkflowClient client, String trustedAutomationCommit) {
    List<DiscoveryJob> jobs = new ArrayList<>();
    for (WorkflowExecutionMetadata execution : openExecutions(client, "CandidateWorkflow")) {
      try {
        List<DiscoveryJob> executionJobs = new ArrayList<>();
        discoverCandidate(execution, executionJobs, trustedAutomationCommit);
        jobs.addAll(executionJobs);
      } catch (RuntimeException e) {
        reportSkippedExecution(execution, e);
      }
    }
    for (WorkflowExecutionMetadata execution : openExecutions(client, "ReleaseWorkflow")) {
      try {
        List<DiscoveryJob> executionJobs = new ArrayList<>();
        discoverRelease(execution, executionJobs, trustedAutomationCommit);
        jobs.addAll(executionJobs);
      } catch (RuntimeException e) {
        reportSkippedExecution(execution, e);
      }
    }
    return jobs;
  }

  private static void discoverCandidate(
      WorkflowExecutionMetadata execution,
      List<DiscoveryJob> jobs,
      String trustedAutomationCommit) {
    String workflowId = execution.getExecution().getWorkflowId();
    String prefix = "sdk-java-release-candidate/";
    if (!workflowId.startsWith(prefix)) {
      throw new IllegalStateException("Unexpected sdk-java candidate Workflow ID.");
    }
    String digest = workflowId.substring(prefix.length());
    CandidateIdentity candidate =
        (CandidateIdentity) execution.getMemo("CandidateIdentity", CandidateIdentity.class);
    if (candidate == null || !candidate.digest().equals(digest)) {
      throw new IllegalStateException("Candidate memo does not match its Workflow ID.");
    }
    requireTrustedDiscoveryCommit(candidate.trustedAutomationCommit, trustedAutomationCommit);
    DiscoveryJob candidateJob =
        new DiscoveryJob("candidate", QueueNames.candidateWorkflowFromDigest(digest));
    candidateJob.automationCommit = candidate.trustedAutomationCommit;
    candidateJob.candidateDigest = digest;
    candidateJob.workflowId = execution.getExecution().getWorkflowId();
    candidateJob.runId = execution.getExecution().getRunId();
    jobs.add(candidateJob);
    CandidateStatus candidateStatus =
        (CandidateStatus)
            execution.getMemo(CandidateWorkflowImpl.STATUS_MEMO_KEY, CandidateStatus.class);
    List<String> pendingPlatforms =
        candidateStatus == null ? ReleasePolicy.NATIVE_PLATFORMS : candidateStatus.pendingPlatforms;
    for (String platform : pendingPlatforms) {
      DiscoveryJob build = new DiscoveryJob("build", QueueNames.buildFromDigest(digest, platform));
      build.platform = platform;
      build.candidateDigest = digest;
      build.tag = candidate.tag;
      build.commitSha = candidate.commitSha;
      build.automationCommit = candidate.trustedAutomationCommit;
      build.runner = runnerFor(platform);
      if (platform.startsWith("macos-") || "windows-amd64".equals(platform)) {
        build.distribution = "graalvm-community";
      }
      jobs.add(build);
    }
    if (candidateStatus != null && candidateStatus.releaseIdentity != null) {
      ReleaseIdentity releaseIdentity = candidateStatus.releaseIdentity;
      releaseIdentity.validate();
      DiscoveryJob release =
          new DiscoveryJob("release", QueueNames.releaseWorkflow(releaseIdentity));
      release.automationCommit = releaseIdentity.candidate.trustedAutomationCommit;
      release.candidateDigest = candidate.digest();
      release.workflowId = QueueNames.releaseWorkflowId(releaseIdentity);
      jobs.add(release);
    }
  }

  private static void discoverRelease(
      WorkflowExecutionMetadata execution,
      List<DiscoveryJob> jobs,
      String trustedAutomationCommit) {
    ReleaseStatus status = releaseStatus(execution);
    ReleaseIdentity releaseIdentity =
        status == null
            ? (ReleaseIdentity)
                execution.getMemo(ReleaseWorkflowImpl.IDENTITY_MEMO_KEY, ReleaseIdentity.class)
            : status.identity;
    if (releaseIdentity == null) {
      throw new IllegalStateException("Release Workflow has no immutable identity memo.");
    }
    releaseIdentity.validate();
    requireTrustedDiscoveryCommit(
        releaseIdentity.candidate.trustedAutomationCommit, trustedAutomationCommit);
    if (!QueueNames.releaseWorkflowId(releaseIdentity)
            .equals(execution.getExecution().getWorkflowId())
        || !QueueNames.releaseWorkflow(releaseIdentity).equals(execution.getTaskQueue())) {
      throw new IllegalStateException("Release identity memo does not match Workflow routing.");
    }
    if (status != null
        && ("PAUSED".equals(status.phase)
            || "BLOCKED".equals(status.phase)
            || "HANDED_OFF".equals(status.phase))) {
      return;
    }
    DiscoveryJob release = new DiscoveryJob("release", execution.getTaskQueue());
    release.automationCommit = releaseIdentity.candidate.trustedAutomationCommit;
    release.workflowId = execution.getExecution().getWorkflowId();
    release.runId = execution.getExecution().getRunId();
    jobs.add(release);
  }

  private static List<DiscoveryJob> discoverPublication(
      WorkflowClient client, String trustedAutomationCommit) {
    List<DiscoveryJob> jobs = new ArrayList<>();
    for (WorkflowExecutionMetadata execution : openExecutions(client, "ReleaseWorkflow")) {
      try {
        List<DiscoveryJob> executionJobs = new ArrayList<>();
        discoverPublication(execution, executionJobs, trustedAutomationCommit);
        jobs.addAll(executionJobs);
      } catch (RuntimeException e) {
        reportSkippedExecution(execution, e);
      }
    }
    return jobs;
  }

  private static void discoverPublication(
      WorkflowExecutionMetadata execution,
      List<DiscoveryJob> jobs,
      String trustedAutomationCommit) {
    ReleaseStatus status = releaseStatus(execution);
    if (status == null || status.identity == null || status.approval == null) {
      return;
    }
    if (!("PREFLIGHT".equals(status.phase)
        || "MAVEN".equals(status.phase)
        || "GITHUB_DRAFT".equals(status.phase)
        || "PUBLISH_GITHUB".equals(status.phase))) {
      return;
    }
    if (status.nextRetryAtMillis > System.currentTimeMillis()) {
      return;
    }
    ReleaseIdentity identity = status.identity;
    identity.validate();
    requireTrustedDiscoveryCommit(
        identity.candidate.trustedAutomationCommit, trustedAutomationCommit);
    if (!QueueNames.releaseWorkflowId(identity).equals(execution.getExecution().getWorkflowId())
        || !QueueNames.releaseWorkflow(identity).equals(execution.getTaskQueue())
        || !execution.getExecution().getRunId().equals(status.approval.runId)) {
      throw new IllegalStateException("Approved release memo does not match its execution.");
    }
    DiscoveryJob job =
        new DiscoveryJob(
            "publication", QueueNames.publication(identity, status.mavenSubmissionGeneration));
    job.workflowId = execution.getExecution().getWorkflowId();
    job.runId = execution.getExecution().getRunId();
    job.tag = identity.candidate.tag;
    job.commitSha = identity.candidate.commitSha;
    job.notesSha256 = identity.candidate.releaseNotesSha256;
    job.manifestSha256 = identity.manifestSha256;
    job.releaseDigest = identity.digest();
    job.candidateDigest = identity.candidate.digest();
    job.approvalRunId = Long.toString(status.approval.githubApprovalRunId);
    job.approvalActor = status.approval.githubActor;
    job.approvalIssueNumber = Long.toString(status.approval.githubIssueNumber);
    job.approvalIssueNodeId = status.approval.githubIssueNodeId;
    job.approvalIssueBodySha256 = status.approval.githubIssueBodySha256;
    job.automationCommit = identity.candidate.trustedAutomationCommit;
    job.phase = status.phase;
    job.nextRetryAtMillis = status.nextRetryAtMillis;
    job.mavenSubmissionGeneration = status.mavenSubmissionGeneration;
    if (status.mavenRetryAuthorization != null) {
      job.mavenRetryAuthorizationSha256 = status.mavenRetryAuthorization.authorizationSha256;
    }
    jobs.add(job);
  }

  private static List<DiscoveryJob> discoverApprovals(
      WorkflowClient client, String trustedAutomationCommit) {
    List<DiscoveryJob> jobs = new ArrayList<>();
    for (WorkflowExecutionMetadata execution : openExecutions(client, "ReleaseWorkflow")) {
      try {
        List<DiscoveryJob> executionJobs = new ArrayList<>();
        discoverApproval(execution, executionJobs, trustedAutomationCommit);
        jobs.addAll(executionJobs);
      } catch (RuntimeException e) {
        reportSkippedExecution(execution, e);
      }
    }
    return jobs;
  }

  private static void discoverApproval(
      WorkflowExecutionMetadata execution,
      List<DiscoveryJob> jobs,
      String trustedAutomationCommit) {
    ReleaseStatus status = releaseStatus(execution);
    if (status == null
        || status.identity == null
        || !"AWAITING_APPROVAL".equals(status.phase)
        || status.approval != null) {
      return;
    }
    ReleaseIdentity identity = status.identity;
    identity.validate();
    requireTrustedDiscoveryCommit(
        identity.candidate.trustedAutomationCommit, trustedAutomationCommit);
    DiscoveryJob job =
        new DiscoveryJob(
            status.approvalRequest == null ? "approval" : "approval-recovery",
            execution.getTaskQueue());
    job.workflowId = execution.getExecution().getWorkflowId();
    job.runId = execution.getExecution().getRunId();
    job.tag = identity.candidate.tag;
    job.commitSha = identity.candidate.commitSha;
    job.notesSha256 = identity.candidate.releaseNotesSha256;
    job.manifestSha256 = identity.manifestSha256;
    job.releaseDigest = identity.digest();
    job.automationCommit = identity.candidate.trustedAutomationCommit;
    if (status.approvalRequest != null) {
      job.approvalIssueNumber = Long.toString(status.approvalRequest.githubIssueNumber);
      job.approvalIssueNodeId = status.approvalRequest.githubIssueNodeId;
      job.approvalIssueBodySha256 = status.approvalRequest.githubIssueBodySha256;
    }
    jobs.add(job);
  }

  private static void approve(WorkflowClient client, Map<String, String> env) {
    String actor = required(env, "GITHUB_TRIGGERING_ACTOR");
    verifyApprover(actor);
    String eventName = required(env, "GITHUB_EVENT_NAME");
    if (!("issues".equals(eventName) || "schedule".equals(eventName))) {
      throw new IllegalStateException(
          "Approval must be delivered by the issue event or its scheduled recovery.");
    }
    long issueNumber = Long.parseLong(required(env, "APPROVAL_ISSUE_NUMBER"));
    WorkflowExecutionMetadata metadata = findApprovalIssue(client, issueNumber);
    if (!metadata.getTaskQueue().startsWith("sdk-java-release-")) {
      throw new IllegalStateException("Release Workflow uses an unexpected Task Queue.");
    }
    WorkerFactory factory = WorkerFactory.newInstance(client);
    factory
        .newWorker(metadata.getTaskQueue())
        .registerWorkflowImplementationTypes(ReleaseWorkflowImpl.class);
    factory.start();
    try {
      WorkflowExecution execution = metadata.getExecution();
      ReleaseWorkflow workflow = releaseStub(client, execution);
      ReleaseStatus status = workflow.status();
      if (status == null || !"AWAITING_APPROVAL".equals(status.phase)) {
        throw new IllegalStateException("The only open release is not awaiting approval.");
      }
      ReleaseIdentity identity = status.identity;
      identity.validate();
      requireTrustedCommit(identity, env);
      if (!QueueNames.releaseWorkflowId(identity).equals(execution.getWorkflowId())
          || !QueueNames.releaseWorkflow(identity).equals(metadata.getTaskQueue())) {
        throw new IllegalStateException("Release identity does not match its Workflow routing.");
      }
      ApprovalEvidence evidence =
          new ApprovalEvidence(
              CandidateIdentity.REPOSITORY,
              identity.digest(),
              execution.getWorkflowId(),
              execution.getRunId(),
              Long.parseLong(required(env, "GITHUB_RUN_ID")),
              actor,
              issueNumber,
              required(env, "APPROVAL_ISSUE_NODE_ID"),
              required(env, "APPROVAL_ISSUE_BODY_SHA256"),
              identity.candidate.trustedAutomationCommit);
      workflow.approve(evidence);
      writeIdentityOutputs(metadata, identity, "APPROVED");
    } finally {
      factory.shutdown();
    }
  }

  private static void requestApproval(WorkflowClient client, Map<String, String> env) {
    String expectedWorkflowId = required(env, "EXPECTED_WORKFLOW_ID");
    List<WorkflowExecutionMetadata> pending =
        openExecutions(client, "ReleaseWorkflow").stream()
            .filter(
                execution -> {
                  ReleaseStatus status = validReleaseStatusOrNull(execution);
                  return status != null
                      && expectedWorkflowId.equals(execution.getExecution().getWorkflowId());
                })
            .collect(Collectors.toList());
    if (pending.size() != 1) {
      throw new IllegalStateException(
          "Approval request does not identify exactly one open release; found "
              + pending.size()
              + ".");
    }
    WorkflowExecutionMetadata metadata = pending.get(0);
    withReleaseWorker(
        client,
        metadata,
        workflow -> {
          ReleaseStatus status = workflow.status();
          ReleaseIdentity identity = status.identity;
          requireTrustedCommit(identity, env);
          ApprovalRequest request =
              new ApprovalRequest(
                  ReleasePolicy.REPOSITORY,
                  identity.digest(),
                  metadata.getExecution().getWorkflowId(),
                  metadata.getExecution().getRunId(),
                  Long.parseLong(required(env, "GITHUB_RUN_ID")),
                  Long.parseLong(required(env, "APPROVAL_ISSUE_NUMBER")),
                  required(env, "APPROVAL_ISSUE_NODE_ID"),
                  required(env, "APPROVAL_ISSUE_BODY_SHA256"),
                  identity.candidate.trustedAutomationCommit);
          if (status.approvalRequest == null) {
            workflow.requestApproval(request);
          } else if (!status.approvalRequest.sameIssue(request)) {
            throw new IllegalStateException(
                "The release already has a different immutable approval issue.");
          }
          writeIdentityOutputs(metadata, identity, status.phase);
        });
  }

  private static void approvalTarget(WorkflowClient client, Map<String, String> env) {
    WorkflowExecutionMetadata target =
        findApprovalIssue(client, Long.parseLong(required(env, "APPROVAL_ISSUE_NUMBER")));
    ReleaseStatus status = validatedReleaseStatus(target);
    writeIdentityOutputs(target, status.identity, status.phase);
  }

  private static void control(
      WorkflowClient client, Map<String, String> env, String action, String tag, String commitSha) {
    String actor = optional(env, "CONTROL_GITHUB_ACTOR", required(env, "GITHUB_TRIGGERING_ACTOR"));
    verifyApprover(actor);
    long githubRunId =
        Long.parseLong(optional(env, "CONTROL_GITHUB_RUN_ID", required(env, "GITHUB_RUN_ID")));
    WorkflowExecutionMetadata metadata = findRelease(client, tag, commitSha);
    withReleaseWorker(
        client,
        metadata,
        workflow -> {
          ReleaseStatus status = workflow.status();
          ReleaseIdentity identity = status.identity;
          requireTrustedCommit(identity, env);
          ControlEvidence evidence = new ControlEvidence();
          evidence.action = action;
          evidence.repository = ReleasePolicy.REPOSITORY;
          evidence.releaseDigest = identity.digest();
          evidence.workflowId = metadata.getExecution().getWorkflowId();
          evidence.runId = metadata.getExecution().getRunId();
          evidence.githubRunId = githubRunId;
          evidence.githubActor = actor;
          evidence.tag = tag;
          evidence.commitSha = commitSha;
          evidence.reason = fixedControlReason(action);
          if ("retry-maven-submission".equals(action)) {
            evidence.mavenSubmissionGeneration =
                Integer.parseInt(required(env, "MAVEN_RETRY_GENERATION"));
            evidence.authorizationSha256 = required(env, "MAVEN_RETRY_AUTHORIZATION_SHA256");
          }
          if ("manual-complete".equals(action)) {
            evidence.githubReleaseUrl = required(env, "MANUAL_GITHUB_RELEASE_URL");
            evidence.mavenCentralUrl = required(env, "MANUAL_MAVEN_CENTRAL_URL");
          }
          evidence.validate();
          ReleaseStatus updated = workflow.control(evidence);
          writeIdentityOutputs(metadata, identity, updated.phase);
          writeStatusOutputs(updated);
          if ("handoff-manual".equals(action)) {
            Path handoff =
                Paths.get(required(env, "RUNNER_TEMP")).resolve("sdk-java-release-handoff.json");
            try {
              Files.write(handoff, GSON.toJson(updated).getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
              throw new IllegalStateException("Unable to write the handoff receipt.", e);
            }
            writeOutput("handoff_file", handoff.toString());
          }
        });
  }

  private static void inspect(WorkflowClient client, String tag, String commitSha) {
    WorkflowExecutionMetadata metadata = findReleaseIncludingClosed(client, tag, commitSha);
    ReleaseStatus status = validatedReleaseStatus(metadata);
    writeIdentityOutputs(metadata, status.identity, status.phase);
    writeStatusOutputs(status);
  }

  private static void inspectIfPresent(WorkflowClient client, String tag, String commitSha) {
    List<WorkflowExecutionMetadata> matches =
        client
            .listExecutions("WorkflowType = 'ReleaseWorkflow'")
            .filter(
                execution -> {
                  ReleaseStatus status = validReleaseStatusOrNull(execution);
                  return status != null
                      && status.identity != null
                      && tag.equals(status.identity.candidate.tag)
                      && commitSha.equals(status.identity.candidate.commitSha);
                })
            .collect(Collectors.toList());
    if (matches.isEmpty()) {
      writeOutput("found", "false");
      writeOutput("phase", "NO_WORKFLOW");
      return;
    }
    if (matches.size() != 1) {
      throw new IllegalStateException("Tag and SHA identify multiple release executions.");
    }
    ReleaseStatus status = validatedReleaseStatus(matches.get(0));
    writeIdentityOutputs(matches.get(0), status.identity, status.phase);
    writeStatusOutputs(status);
    writeOutput("found", "true");
  }

  private static void publicationInput(
      WorkflowClient client, String tag, String commitSha, Path output) throws IOException {
    WorkflowExecutionMetadata metadata = findRelease(client, tag, commitSha);
    ReleaseStatus status = validatedReleaseStatus(metadata);
    if (status == null || status.identity == null || status.approval == null) {
      throw new IllegalStateException("The exact release has no approved publication input.");
    }
    PublicationInput input =
        new PublicationInput(
            status.identity,
            status.approval,
            metadata.getExecution().getWorkflowId(),
            metadata.getExecution().getRunId());
    input.mavenSubmissionGeneration = status.mavenSubmissionGeneration;
    input.mavenRetryAuthorization = status.mavenRetryAuthorization;
    Files.write(output, GSON.toJson(input).getBytes(StandardCharsets.UTF_8));
    writeIdentityOutputs(metadata, status.identity, status.phase);
    writeOutput("approval_run_id", Long.toString(status.approval.githubApprovalRunId));
    writeOutput("approval_actor", status.approval.githubActor);
    writeOutput("approval_issue_number", Long.toString(status.approval.githubIssueNumber));
    writeOutput("approval_issue_node_id", status.approval.githubIssueNodeId);
    writeOutput("approval_issue_body_sha256", status.approval.githubIssueBodySha256);
    writeOutput("maven_submission_generation", Integer.toString(status.mavenSubmissionGeneration));
    writeOutput(
        "maven_retry_authorization_sha256",
        status.mavenRetryAuthorization == null
            ? ""
            : status.mavenRetryAuthorization.authorizationSha256);
    writeOutput("publication_input_file", output.toString());
  }

  private static void runWorker(
      WorkflowClient client, String role, String taskQueue, Map<String, String> env)
      throws InterruptedException {
    if (!taskQueue.startsWith("sdk-java-release-")) {
      throw new IllegalArgumentException("Refusing to poll a non-release Task Queue.");
    }
    WorkerFactory factory = WorkerFactory.newInstance(client);
    Worker worker = factory.newWorker(taskQueue);
    CountDownLatch activityCompleted = new CountDownLatch(1);
    CountDownLatch activityStarted = new CountDownLatch(1);
    AtomicReference<Throwable> activityFailure = new AtomicReference<>();
    Consumer<Throwable> recordActivityCompletion =
        failure -> {
          activityFailure.set(failure);
          activityCompleted.countDown();
        };
    switch (role) {
      case "candidate":
        worker.registerWorkflowImplementationTypes(CandidateWorkflowImpl.class);
        worker.registerActivitiesImplementations(
            new CandidateStateActivitiesImpl(REPOSITORY_ROOT, env));
        break;
      case "release":
        worker.registerWorkflowImplementationTypes(ReleaseWorkflowImpl.class);
        break;
      case "build":
        worker.registerActivitiesImplementations(
            new BuildActivitiesImpl(
                REPOSITORY_ROOT,
                sourceRoot(env),
                required(env, "RELEASE_AUTOMATION_REF"),
                env,
                activityStarted::countDown,
                recordActivityCompletion));
        break;
      case "publication":
        worker.registerActivitiesImplementations(
            new PublicationActivitiesImpl(
                REPOSITORY_ROOT,
                sourceRoot(env),
                env,
                activityStarted::countDown,
                recordActivityCompletion));
        break;
      default:
        throw new IllegalArgumentException("Unknown Worker role: " + role);
    }
    factory.start();
    boolean activityRole = "build".equals(role) || "publication".equals(role);
    boolean processed = false;
    if (activityRole
        && activityStarted.await(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS)) {
      processed = activityCompleted.await(Duration.ofMinutes(98).toMillis(), TimeUnit.MILLISECONDS);
    } else if (!activityRole) {
      processed = awaitWindow(Duration.ofMinutes(10));
      failOnUnrecoveredWorkflowTaskFailure(client, env);
    }
    writeOutput(
        "worker_outcome", processed ? "activity-attempt-finished" : "capacity-window-ended");
    factory.shutdown();
    factory.awaitTermination(10, java.util.concurrent.TimeUnit.MINUTES);
    if (activityFailure.get() != null) {
      Throwable failure = activityFailure.get();
      if (failure instanceof ApplicationFailure
          && ((ApplicationFailure) failure).isNonRetryable()) {
        throw new IllegalStateException(
            "The release Activity reached a durable non-retryable failure.", failure);
      }
      writeOutput("worker_outcome", "activity-attempt-failed-temporal-will-retry");
      throw new IllegalStateException(
          "The release Activity attempt failed; Temporal retained its durable retry state and scheduled recovery.",
          failure);
    }
  }

  private static boolean awaitWindow(Duration pollingWindow) throws InterruptedException {
    Thread.sleep(pollingWindow.toMillis());
    return false;
  }

  private static void failOnUnrecoveredWorkflowTaskFailure(
      WorkflowClient client, Map<String, String> env) {
    String workflowId = required(env, "EXPECTED_WORKFLOW_ID");
    String runId = env.get("EXPECTED_RUN_ID");
    io.temporal.common.WorkflowExecutionHistory history =
        runId == null || runId.isEmpty()
            ? client.fetchHistory(workflowId)
            : client.fetchHistory(workflowId, runId);
    String failure = unrecoveredWorkflowFailure(history.getEvents());
    if (failure != null) {
      writeOutput("worker_outcome", failure);
      throw new IllegalStateException("The release Workflow failed: " + failure + ".");
    }
  }

  static String unrecoveredWorkflowFailure(
      Iterable<io.temporal.api.history.v1.HistoryEvent> events) {
    long lastCompletedTask = -1;
    long lastFailedTask = -1;
    String terminalFailure = null;
    for (io.temporal.api.history.v1.HistoryEvent event : events) {
      switch (event.getEventType()) {
        case EVENT_TYPE_WORKFLOW_TASK_COMPLETED:
          lastCompletedTask = event.getEventId();
          break;
        case EVENT_TYPE_WORKFLOW_TASK_FAILED:
          lastFailedTask = event.getEventId();
          break;
        case EVENT_TYPE_WORKFLOW_TASK_TIMED_OUT:
          lastFailedTask = event.getEventId();
          break;
        case EVENT_TYPE_WORKFLOW_EXECUTION_FAILED:
          terminalFailure = "workflow-execution-failed";
          break;
        case EVENT_TYPE_WORKFLOW_EXECUTION_TIMED_OUT:
          terminalFailure = "workflow-execution-timed-out";
          break;
        case EVENT_TYPE_WORKFLOW_EXECUTION_TERMINATED:
          terminalFailure = "workflow-execution-terminated";
          break;
        case EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED:
          terminalFailure = "workflow-execution-canceled";
          break;
        default:
          break;
      }
    }
    if (terminalFailure != null) {
      return terminalFailure;
    }
    return lastFailedTask > lastCompletedTask ? "workflow-task-failed-or-timed-out" : null;
  }

  private static List<WorkflowExecutionMetadata> openExecutions(
      WorkflowClient client, String workflowType) {
    String query =
        "ExecutionStatus = 'Running' AND WorkflowType = '" + workflowType.replace("'", "''") + "'";
    return client.listExecutions(query).collect(Collectors.toList());
  }

  private static void reportSkippedExecution(
      WorkflowExecutionMetadata execution, RuntimeException failure) {
    System.err.println(
        "Skipping malformed release execution "
            + execution.getExecution().getWorkflowId()
            + ": "
            + value(failure.getMessage()));
  }

  private static String runnerFor(String platform) {
    switch (platform) {
      case "macos-amd64":
        return "macos-15-intel";
      case "macos-arm64":
        return "macos-latest";
      case "linux-arm64":
        return "ubuntu-24.04-arm";
      case "windows-amd64":
        return "windows-latest";
      default:
        return "ubuntu-latest";
    }
  }

  private static ReleaseWorkflow releaseStub(WorkflowClient client, WorkflowExecution execution) {
    return client.newWorkflowStub(
        ReleaseWorkflow.class,
        WorkflowTargetOptions.newBuilder().setWorkflowExecution(execution).build());
  }

  private static ReleaseStatus releaseStatus(WorkflowExecutionMetadata execution) {
    return (ReleaseStatus)
        execution.getMemo(ReleaseWorkflowImpl.STATUS_MEMO_KEY, ReleaseStatus.class);
  }

  private static ReleaseStatus validatedReleaseStatus(WorkflowExecutionMetadata execution) {
    ReleaseStatus status = releaseStatus(execution);
    if (status == null || status.identity == null) {
      throw new IllegalStateException("Release execution has no immutable status identity.");
    }
    status.identity.validate();
    if (!QueueNames.releaseWorkflowId(status.identity)
            .equals(execution.getExecution().getWorkflowId())
        || !QueueNames.releaseWorkflow(status.identity).equals(execution.getTaskQueue())) {
      throw new IllegalStateException("Release status identity does not match Workflow routing.");
    }
    if (status.approvalRequest != null) {
      status.approvalRequest.validate();
      if (!execution.getExecution().getRunId().equals(status.approvalRequest.runId)) {
        throw new IllegalStateException("Approval request is bound to another Workflow run.");
      }
    }
    if (status.approval != null) {
      status.approval.validate();
      if (!execution.getExecution().getRunId().equals(status.approval.runId)) {
        throw new IllegalStateException("Approval is bound to another Workflow run.");
      }
    }
    return status;
  }

  private static ReleaseStatus validReleaseStatusOrNull(WorkflowExecutionMetadata execution) {
    try {
      return validatedReleaseStatus(execution);
    } catch (RuntimeException failure) {
      reportSkippedExecution(execution, failure);
      return null;
    }
  }

  private static WorkflowExecutionMetadata findApprovalIssue(
      WorkflowClient client, long issueNumber) {
    List<WorkflowExecutionMetadata> matches =
        openExecutions(client, "ReleaseWorkflow").stream()
            .filter(
                execution -> {
                  ReleaseStatus status = validReleaseStatusOrNull(execution);
                  return status != null
                      && "AWAITING_APPROVAL".equals(status.phase)
                      && status.approvalRequest != null
                      && status.approvalRequest.githubIssueNumber == issueNumber;
                })
            .collect(Collectors.toList());
    if (matches.size() != 1) {
      throw new IllegalStateException(
          "The Actions run is not bound to exactly one pending release.");
    }
    return matches.get(0);
  }

  private static WorkflowExecutionMetadata findRelease(
      WorkflowClient client, String tag, String commitSha) {
    return findRelease(openExecutions(client, "ReleaseWorkflow"), tag, commitSha);
  }

  private static WorkflowExecutionMetadata findReleaseIncludingClosed(
      WorkflowClient client, String tag, String commitSha) {
    List<WorkflowExecutionMetadata> open = openExecutions(client, "ReleaseWorkflow");
    try {
      return findRelease(open, tag, commitSha);
    } catch (IllegalStateException ignored) {
      return findRelease(
          client.listExecutions("WorkflowType = 'ReleaseWorkflow'").collect(Collectors.toList()),
          tag,
          commitSha);
    }
  }

  private static WorkflowExecutionMetadata findRelease(
      List<WorkflowExecutionMetadata> executions, String tag, String commitSha) {
    List<WorkflowExecutionMetadata> matches =
        executions.stream()
            .filter(
                execution -> {
                  ReleaseStatus status = validReleaseStatusOrNull(execution);
                  return status != null
                      && status.identity != null
                      && tag.equals(status.identity.candidate.tag)
                      && commitSha.equals(status.identity.candidate.commitSha);
                })
            .collect(Collectors.toList());
    if (matches.size() != 1) {
      throw new IllegalStateException("Tag and SHA do not identify exactly one release execution.");
    }
    return matches.get(0);
  }

  private static void withReleaseWorker(
      WorkflowClient client,
      WorkflowExecutionMetadata metadata,
      Consumer<ReleaseWorkflow> operation) {
    WorkerFactory factory = WorkerFactory.newInstance(client);
    factory
        .newWorker(metadata.getTaskQueue())
        .registerWorkflowImplementationTypes(ReleaseWorkflowImpl.class);
    factory.start();
    try {
      operation.accept(releaseStub(client, metadata.getExecution()));
    } finally {
      factory.shutdown();
    }
  }

  private static void requireTrustedCommit(ReleaseIdentity identity, Map<String, String> env) {
    if (!identity.candidate.trustedAutomationCommit.equals(
        required(env, "RELEASE_AUTOMATION_REF"))) {
      throw new IllegalStateException(
          "Actions did not check out this release's trusted Worker commit.");
    }
  }

  private static void requireTrustedDiscoveryCommit(String actual, String allowedCommits) {
    if (!Arrays.asList(allowedCommits.split(",")).contains(actual)) {
      throw new IllegalStateException(
          "Release identity selects an automation commit outside the protected allowlist.");
    }
  }

  private static void writeIdentityOutputs(
      WorkflowExecutionMetadata metadata, ReleaseIdentity identity, String phase) {
    writeOutput("workflow_id", metadata.getExecution().getWorkflowId());
    writeOutput("run_id", metadata.getExecution().getRunId());
    writeOutput("tag", identity.candidate.tag);
    writeOutput("commit_sha", identity.candidate.commitSha);
    writeOutput("notes_sha256", identity.candidate.releaseNotesSha256);
    writeOutput("manifest_sha256", identity.manifestSha256);
    writeOutput("release_digest", identity.digest());
    writeOutput("automation_commit", identity.candidate.trustedAutomationCommit);
    writeOutput("phase", phase);
  }

  private static void writeStatusOutputs(ReleaseStatus status) {
    writeOutput("last_completed_stage", value(status.lastCompletedStage));
    writeOutput("last_error", value(status.lastError));
    writeOutput("blocked_at_millis", Long.toString(status.blockedAtMillis));
    writeOutput("maven_central_url", value(status.mavenCentralUrl));
    writeOutput("sonatype_repository_id", value(status.sonatypeRepositoryId));
    writeOutput("portal_deployment_id", value(status.portalDeploymentId));
    writeOutput("github_draft_url", value(status.githubDraftUrl));
    writeOutput("github_release_url", value(status.githubReleaseUrl));
    writeOutput("maven_submission_generation", Integer.toString(status.mavenSubmissionGeneration));
    writeOutput("stage_attempt", Integer.toString(status.stageAttempt));
    writeOutput("stage_started_at_millis", Long.toString(status.stageStartedAtMillis));
    writeOutput("next_retry_at_millis", Long.toString(status.nextRetryAtMillis));
  }

  private static String value(String value) {
    return value == null ? "" : value.replace('\n', ' ');
  }

  private static String optional(Map<String, String> environment, String name, String fallback) {
    String value = environment.get(name);
    return value == null || value.isEmpty() ? fallback : value;
  }

  private static String fixedControlReason(String action) {
    switch (action) {
      case "pause":
        return "Release manager paused Temporal publication.";
      case "resume":
        return "Release manager resumed Temporal publication.";
      case "handoff-manual":
        return "Release manager transferred ownership to the emergency workflow.";
      case "retry-maven-submission":
        return "Release manager inspected Sonatype and authorized one new staging generation.";
      case "manual-complete":
        return "Emergency automation reconciled every immutable publication side effect.";
      default:
        throw new IllegalArgumentException("Unknown release control action.");
    }
  }

  private static <T> T read(String path, Class<T> type) throws IOException {
    return GSON.fromJson(
        new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8), type);
  }

  private static void writeOutput(String name, String value) {
    String output = System.getenv("GITHUB_OUTPUT");
    if (output == null || output.isEmpty()) {
      System.out.println(name + "=" + value);
      return;
    }
    try {
      Files.write(
          Paths.get(output),
          Arrays.asList(name + "=" + value),
          StandardCharsets.UTF_8,
          java.nio.file.StandardOpenOption.APPEND);
    } catch (IOException e) {
      throw new IllegalStateException("Unable to write GitHub Actions output.", e);
    }
  }

  private static String required(Map<String, String> env, String name) {
    String value = env.get(name);
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException("Required Actions value is missing: " + name);
    }
    return value;
  }

  private static Path sourceRoot(Map<String, String> env) {
    Path path = Paths.get(required(env, "RELEASE_SOURCE_DIR")).toAbsolutePath().normalize();
    if (!Files.isDirectory(path)) {
      throw new IllegalArgumentException("RELEASE_SOURCE_DIR is not a directory.");
    }
    return path;
  }

  private static void verifyApprover(String actor) {
    ProcessBuilder process =
        new ProcessBuilder(
                "bash",
                REPOSITORY_ROOT
                    .resolve(".github/scripts/temporal-release/verify-approver.sh")
                    .toAbsolutePath()
                    .toString()
                    .replace('\\', '/'),
                actor)
            .directory(REPOSITORY_ROOT.toFile())
            .inheritIO();
    try {
      int status = process.start().waitFor();
      if (status == 43) {
        throw new IllegalArgumentException("GitHub actor is not an sdk-java release manager.");
      }
      if (status != 0) {
        throw new IllegalStateException(
            "GitHub release-manager membership is temporarily unavailable.");
      }
    } catch (IOException e) {
      throw new IllegalStateException("Unable to run the fixed approver check.", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Approver check was interrupted.", e);
    }
  }

  private static Path repositoryRoot() {
    String configured = System.getProperty("releaseAutomation.repositoryRoot");
    if (configured == null || configured.isEmpty()) {
      throw new IllegalStateException("The trusted repository root JVM property is missing.");
    }
    Path root = Paths.get(configured).toAbsolutePath().normalize();
    if (!Files.isRegularFile(root.resolve(".github/scripts/temporal-release/verify-approver.sh"))) {
      throw new IllegalStateException("The trusted repository root has an unexpected layout.");
    }
    return root;
  }

  private static void requireArguments(String[] args, int expected) {
    if (args.length != expected) {
      throw new IllegalArgumentException("Unexpected command arguments.");
    }
  }
}
