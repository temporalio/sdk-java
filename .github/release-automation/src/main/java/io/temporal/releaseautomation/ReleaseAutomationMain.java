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
import java.util.stream.Collectors;

public final class ReleaseAutomationMain {
  private static final Gson GSON = new Gson();
  private static final Path REPOSITORY_ROOT = repositoryRoot();

  private ReleaseAutomationMain() {}

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      throw new IllegalArgumentException(
          "Expected start-candidate, discover, approval-target, approval-request, approve, control, inspect, emergency-inspect-export, emergency-export, or worker.");
    }
    Map<String, String> environment = System.getenv();
    try (TemporalConnection temporal = TemporalConnection.fromEnvironment(environment)) {
      switch (args[0]) {
        case "start-candidate":
          requireArguments(args, 2);
          startCandidate(temporal.client, read(args[1], CandidateIdentity.class));
          return;
        case "discover":
          requireArguments(args, 2);
          discover(temporal.client, args[1]);
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
        case "emergency-export":
          requireArguments(args, 3);
          emergencyExport(temporal.client, environment, args[1], args[2]);
          return;
        case "emergency-inspect-export":
          requireArguments(args, 3);
          emergencyInspectExport(temporal.client, environment, args[1], args[2]);
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

  private static void discover(WorkflowClient client, String scope) {
    List<DiscoveryJob> jobs;
    if ("unprivileged".equals(scope)) {
      jobs = discoverUnprivileged(client);
    } else if ("publication".equals(scope)) {
      jobs = discoverPublication(client);
    } else {
      throw new IllegalArgumentException("Discovery scope must be unprivileged or publication.");
    }
    JsonObject matrix = new JsonObject();
    matrix.add("include", GSON.toJsonTree(jobs));
    writeOutput("matrix", GSON.toJson(matrix));
    writeOutput("count", Integer.toString(jobs.size()));
  }

  private static List<DiscoveryJob> discoverUnprivileged(WorkflowClient client) {
    List<DiscoveryJob> jobs = new ArrayList<>();
    for (WorkflowExecutionMetadata execution : openExecutions(client, "CandidateWorkflow")) {
      String workflowId = execution.getExecution().getWorkflowId();
      String prefix = "sdk-java-release-candidate/";
      if (!workflowId.startsWith(prefix)) {
        throw new IllegalStateException("Unexpected sdk-java candidate Workflow ID.");
      }
      String digest = workflowId.substring(prefix.length());
      jobs.add(new DiscoveryJob("candidate", QueueNames.candidateWorkflowFromDigest(digest)));
      CandidateIdentity candidate =
          (CandidateIdentity) execution.getMemo("CandidateIdentity", CandidateIdentity.class);
      if (candidate == null || !candidate.digest().equals(digest)) {
        throw new IllegalStateException("Candidate memo does not match its Workflow ID.");
      }
      jobs.get(jobs.size() - 1).automationCommit = candidate.trustedAutomationCommit;
      CandidateStatus candidateStatus =
          (CandidateStatus)
              execution.getMemo(CandidateWorkflowImpl.STATUS_MEMO_KEY, CandidateStatus.class);
      List<String> pendingPlatforms =
          candidateStatus == null
              ? ReleasePolicy.NATIVE_PLATFORMS
              : candidateStatus.pendingPlatforms;
      for (String platform : pendingPlatforms) {
        DiscoveryJob build =
            new DiscoveryJob("build", QueueNames.buildFromDigest(digest, platform));
        build.platform = platform;
        build.commitSha = candidate.commitSha;
        build.automationCommit = candidate.trustedAutomationCommit;
        build.runner = runnerFor(platform);
        if (platform.startsWith("macos-") || "windows-amd64".equals(platform)) {
          build.distribution = "graalvm";
        }
        jobs.add(build);
      }
    }
    for (WorkflowExecutionMetadata execution : openExecutions(client, "ReleaseWorkflow")) {
      ReleaseStatus status = releaseStatus(execution);
      if (status == null
          || status.identity == null
          || "PAUSED".equals(status.phase)
          || "HANDED_OFF".equals(status.phase)) {
        continue;
      }
      DiscoveryJob release = new DiscoveryJob("release", execution.getTaskQueue());
      release.automationCommit = status.identity.candidate.trustedAutomationCommit;
      release.workflowId = execution.getExecution().getWorkflowId();
      release.runId = execution.getExecution().getRunId();
      jobs.add(release);
    }
    return jobs;
  }

  private static List<DiscoveryJob> discoverPublication(WorkflowClient client) {
    List<DiscoveryJob> jobs = new ArrayList<>();
    for (WorkflowExecutionMetadata execution : openExecutions(client, "ReleaseWorkflow")) {
      ReleaseStatus status = releaseStatus(execution);
      if (status == null || status.identity == null || status.approval == null) {
        continue;
      }
      if (!("PREFLIGHT".equals(status.phase)
          || "MAVEN".equals(status.phase)
          || "GITHUB_DRAFT".equals(status.phase)
          || "PUBLISH_GITHUB".equals(status.phase))) {
        continue;
      }
      ReleaseIdentity identity = status.identity;
      identity.validate();
      if (!QueueNames.releaseWorkflowId(identity).equals(execution.getExecution().getWorkflowId())
          || !QueueNames.releaseWorkflow(identity).equals(execution.getTaskQueue())
          || !execution.getExecution().getRunId().equals(status.approval.runId)) {
        throw new IllegalStateException("Approved release memo does not match its execution.");
      }
      DiscoveryJob job = new DiscoveryJob("publication", QueueNames.publication(identity));
      job.workflowId = execution.getExecution().getWorkflowId();
      job.runId = execution.getExecution().getRunId();
      job.tag = identity.candidate.tag;
      job.commitSha = identity.candidate.commitSha;
      job.notesSha256 = identity.candidate.releaseNotesSha256;
      job.manifestSha256 = identity.manifestSha256;
      job.releaseDigest = identity.digest();
      job.approvalRunId = Long.toString(status.approval.githubApprovalRunId);
      job.approvalActor = status.approval.githubActor;
      job.approvalIssueNumber = Long.toString(status.approval.githubIssueNumber);
      job.approvalIssueNodeId = status.approval.githubIssueNodeId;
      job.approvalIssueBodySha256 = status.approval.githubIssueBodySha256;
      job.automationCommit = identity.candidate.trustedAutomationCommit;
      job.phase = status.phase;
      jobs.add(job);
    }
    return jobs;
  }

  private static void approve(WorkflowClient client, Map<String, String> env) {
    String actor = required(env, "GITHUB_TRIGGERING_ACTOR");
    verifyApprover(actor);
    if (!"issues".equals(required(env, "GITHUB_EVENT_NAME"))) {
      throw new IllegalStateException("Approval must be delivered by the recorded issue event.");
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
    verifyApprover(required(env, "GITHUB_TRIGGERING_ACTOR"));
    List<WorkflowExecutionMetadata> pending =
        openExecutions(client, "ReleaseWorkflow").stream()
            .filter(
                execution -> {
                  ReleaseStatus status = releaseStatus(execution);
                  return status != null
                      && "AWAITING_APPROVAL".equals(status.phase)
                      && status.approvalRequest == null;
                })
            .collect(Collectors.toList());
    if (pending.size() != 1) {
      throw new IllegalStateException(
          "Approval request requires exactly one unrequested release; found "
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
          workflow.requestApproval(request);
          writeIdentityOutputs(metadata, identity, "AWAITING_APPROVAL");
        });
  }

  private static void approvalTarget(WorkflowClient client, Map<String, String> env) {
    WorkflowExecutionMetadata target;
    String issueNumber = env.get("APPROVAL_ISSUE_NUMBER");
    if (issueNumber == null || issueNumber.isEmpty() || "0".equals(issueNumber)) {
      List<WorkflowExecutionMetadata> pending =
          openExecutions(client, "ReleaseWorkflow").stream()
              .filter(
                  execution -> {
                    ReleaseStatus status = releaseStatus(execution);
                    return status != null
                        && "AWAITING_APPROVAL".equals(status.phase)
                        && status.approvalRequest == null;
                  })
              .collect(Collectors.toList());
      if (pending.size() != 1) {
        throw new IllegalStateException("There is not exactly one unrequested release approval.");
      }
      target = pending.get(0);
    } else {
      target = findApprovalIssue(client, Long.parseLong(issueNumber));
    }
    ReleaseStatus status = releaseStatus(target);
    writeIdentityOutputs(target, status.identity, status.phase);
  }

  private static void control(
      WorkflowClient client, Map<String, String> env, String action, String tag, String commitSha) {
    String actor = required(env, "GITHUB_TRIGGERING_ACTOR");
    verifyApprover(actor);
    WorkflowExecutionMetadata metadata = findRelease(client, tag, commitSha);
    withReleaseWorker(
        client,
        metadata,
        workflow -> {
          ReleaseStatus status = workflow.status();
          ReleaseIdentity identity = status.identity;
          requireTrustedCommit(identity, env);
          ControlEvidence evidence =
              new ControlEvidence(
                  action,
                  ReleasePolicy.REPOSITORY,
                  identity.digest(),
                  metadata.getExecution().getWorkflowId(),
                  metadata.getExecution().getRunId(),
                  Long.parseLong(required(env, "GITHUB_RUN_ID")),
                  actor,
                  tag,
                  commitSha,
                  fixedControlReason(action));
          ReleaseStatus updated = workflow.control(evidence);
          writeIdentityOutputs(metadata, identity, updated.phase);
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
    WorkflowExecutionMetadata metadata = findRelease(client, tag, commitSha);
    ReleaseStatus status = releaseStatus(metadata);
    writeIdentityOutputs(metadata, status.identity, status.phase);
  }

  private static void emergencyExport(
      WorkflowClient client, Map<String, String> env, String tag, String commitSha) {
    exportEmergencyInput(client, env, tag, commitSha, true);
  }

  private static void emergencyInspectExport(
      WorkflowClient client, Map<String, String> env, String tag, String commitSha) {
    exportEmergencyInput(client, env, tag, commitSha, false);
  }

  private static void exportEmergencyInput(
      WorkflowClient client,
      Map<String, String> env,
      String tag,
      String commitSha,
      boolean requireHandoff) {
    String actor = required(env, "GITHUB_TRIGGERING_ACTOR");
    verifyApprover(actor);
    WorkflowExecutionMetadata metadata = findRelease(client, tag, commitSha);
    ReleaseStatus status = releaseStatus(metadata);
    if (requireHandoff
        && (!"HANDED_OFF".equals(status.phase)
            || status.control == null
            || !"handoff-manual".equals(status.control.action))) {
      throw new IllegalStateException("Emergency export requires a durable manual handoff.");
    }
    ReleaseIdentity identity = status.identity;
    requireTrustedCommit(identity, env);
    ApprovalEvidence emergencyAuthorization =
        new ApprovalEvidence(
            ReleasePolicy.REPOSITORY,
            identity.digest(),
            metadata.getExecution().getWorkflowId(),
            metadata.getExecution().getRunId(),
            Long.parseLong(required(env, "GITHUB_RUN_ID")),
            actor,
            Long.parseLong(required(env, "GITHUB_RUN_ID")),
            "EMERGENCY_" + required(env, "GITHUB_RUN_ID"),
            Digests.sha256("emergency-handoff\n" + identity.digest()),
            identity.candidate.trustedAutomationCommit);
    PublicationInput input =
        new PublicationInput(
            identity,
            emergencyAuthorization,
            metadata.getExecution().getWorkflowId(),
            metadata.getExecution().getRunId());
    input.emergencyHandoff = "HANDED_OFF".equals(status.phase);
    input.handoff = status.control;
    Path export = Paths.get(required(env, "RUNNER_TEMP")).resolve("sdk-java-emergency-input.json");
    try {
      Files.write(export, GSON.toJson(input).getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new IllegalStateException("Unable to write emergency release input.", e);
    }
    writeIdentityOutputs(metadata, identity, status.phase);
    writeOutput("release_input_file", export.toString());
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
    AtomicReference<Throwable> activityFailure = new AtomicReference<>();
    Consumer<Throwable> recordActivityCompletion =
        failure -> {
          activityFailure.set(failure);
          activityCompleted.countDown();
        };
    switch (role) {
      case "candidate":
        worker.registerWorkflowImplementationTypes(CandidateWorkflowImpl.class);
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
                recordActivityCompletion));
        break;
      case "publication":
        worker.registerActivitiesImplementations(
            new PublicationActivitiesImpl(
                REPOSITORY_ROOT, sourceRoot(env), env, recordActivityCompletion));
        break;
      default:
        throw new IllegalArgumentException("Unknown Worker role: " + role);
    }
    factory.start();
    Duration pollingWindow =
        ("build".equals(role) || "publication".equals(role))
            ? Duration.ofMinutes(100)
            : Duration.ofMinutes(10);
    boolean processed =
        ("build".equals(role) || "publication".equals(role))
            ? activityCompleted.await(pollingWindow.toMillis(), TimeUnit.MILLISECONDS)
            : awaitWindow(pollingWindow);
    writeOutput(
        "worker_outcome", processed ? "activity-attempt-finished" : "capacity-window-ended");
    factory.shutdown();
    factory.awaitTermination(10, java.util.concurrent.TimeUnit.MINUTES);
    if (activityFailure.get() != null) {
      throw new IllegalStateException(
          "The release Activity attempt failed; Temporal retained its retry state.",
          activityFailure.get());
    }
  }

  private static boolean awaitWindow(Duration pollingWindow) throws InterruptedException {
    Thread.sleep(pollingWindow.toMillis());
    return false;
  }

  private static List<WorkflowExecutionMetadata> openExecutions(
      WorkflowClient client, String workflowType) {
    String query =
        "ExecutionStatus = 'Running' AND WorkflowType = '" + workflowType.replace("'", "''") + "'";
    return client.listExecutions(query).collect(Collectors.toList());
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

  private static WorkflowExecutionMetadata findApprovalIssue(
      WorkflowClient client, long issueNumber) {
    List<WorkflowExecutionMetadata> matches =
        openExecutions(client, "ReleaseWorkflow").stream()
            .filter(
                execution -> {
                  ReleaseStatus status = releaseStatus(execution);
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
    List<WorkflowExecutionMetadata> matches =
        openExecutions(client, "ReleaseWorkflow").stream()
            .filter(
                execution -> {
                  ReleaseStatus status = releaseStatus(execution);
                  return status != null
                      && status.identity != null
                      && tag.equals(status.identity.candidate.tag)
                      && commitSha.equals(status.identity.candidate.commitSha);
                })
            .collect(Collectors.toList());
    if (matches.size() != 1) {
      throw new IllegalStateException("Tag and SHA do not identify exactly one open release.");
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

  private static String fixedControlReason(String action) {
    switch (action) {
      case "pause":
        return "Release manager paused Temporal publication.";
      case "resume":
        return "Release manager resumed Temporal publication.";
      case "handoff-manual":
        return "Release manager transferred ownership to the emergency workflow.";
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
      if (process.start().waitFor() != 0) {
        throw new IllegalArgumentException("GitHub actor is not an sdk-java release manager.");
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
