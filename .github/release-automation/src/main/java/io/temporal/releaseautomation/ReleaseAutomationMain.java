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
import java.util.stream.Collectors;

public final class ReleaseAutomationMain {
  private static final Gson GSON = new Gson();
  private static final Path REPOSITORY_ROOT = Paths.get("").toAbsolutePath().normalize();

  private ReleaseAutomationMain() {}

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      throw new IllegalArgumentException("Expected start-candidate, discover, approve, or worker.");
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
      for (String platform : ReleasePolicy.NATIVE_PLATFORMS) {
        DiscoveryJob build =
            new DiscoveryJob("build", QueueNames.buildFromDigest(digest, platform));
        build.platform = platform;
        build.commitSha = candidate.commitSha;
        build.runner = runnerFor(platform);
        if (platform.startsWith("macos-") || "windows-amd64".equals(platform)) {
          build.distribution = "graalvm";
        }
        jobs.add(build);
      }
    }
    for (WorkflowExecutionMetadata execution : openExecutions(client, "ReleaseWorkflow")) {
      jobs.add(new DiscoveryJob("release", execution.getTaskQueue()));
    }
    return jobs;
  }

  private static List<DiscoveryJob> discoverPublication(WorkflowClient client) {
    List<DiscoveryJob> jobs = new ArrayList<>();
    for (WorkflowExecutionMetadata execution : openExecutions(client, "ReleaseWorkflow")) {
      ReleaseStatus status =
          (ReleaseStatus)
              execution.getMemo(ReleaseWorkflowImpl.STATUS_MEMO_KEY, ReleaseStatus.class);
      if (status == null || status.identity == null || status.approval == null) {
        continue;
      }
      if (!"PUBLISHING".equals(status.phase)) {
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
      jobs.add(job);
    }
    return jobs;
  }

  private static void approve(WorkflowClient client, Map<String, String> env) {
    String actor = required(env, "GITHUB_ACTOR");
    verifyApprover(actor);
    List<WorkflowExecutionMetadata> open = openExecutions(client, "ReleaseWorkflow");
    if (open.size() != 1) {
      throw new IllegalStateException(
          "Approval requires exactly one open sdk-java release; found " + open.size() + ".");
    }
    WorkflowExecutionMetadata metadata = open.get(0);
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
              required(env, "RELEASE_AUTOMATION_REF"));
      workflow.approve(evidence);
      writeOutput("workflow_id", execution.getWorkflowId());
      writeOutput("run_id", execution.getRunId());
      writeOutput("tag", identity.candidate.tag);
      writeOutput("release_digest", identity.digest());
    } finally {
      factory.shutdown();
    }
  }

  private static void runWorker(
      WorkflowClient client, String role, String taskQueue, Map<String, String> env)
      throws InterruptedException {
    if (!taskQueue.startsWith("sdk-java-release-")) {
      throw new IllegalArgumentException("Refusing to poll a non-release Task Queue.");
    }
    WorkerFactory factory = WorkerFactory.newInstance(client);
    Worker worker = factory.newWorker(taskQueue);
    switch (role) {
      case "candidate":
        worker.registerWorkflowImplementationTypes(CandidateWorkflowImpl.class);
        break;
      case "release":
        worker.registerWorkflowImplementationTypes(ReleaseWorkflowImpl.class);
        break;
      case "build":
        worker.registerActivitiesImplementations(
            new BuildActivitiesImpl(REPOSITORY_ROOT, sourceRoot(env)));
        break;
      case "publication":
        worker.registerActivitiesImplementations(
            new PublicationActivitiesImpl(REPOSITORY_ROOT, sourceRoot(env), env));
        break;
      default:
        throw new IllegalArgumentException("Unknown Worker role: " + role);
    }
    factory.start();
    Duration pollingWindow =
        ("build".equals(role) || "publication".equals(role))
            ? Duration.ofMinutes(45)
            : Duration.ofMinutes(5);
    Thread.sleep(pollingWindow.toMillis());
    factory.shutdown();
    factory.awaitTermination(10, java.util.concurrent.TimeUnit.MINUTES);
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
                REPOSITORY_ROOT
                    .resolve(".github/scripts/temporal-release/verify-approver.sh")
                    .toString(),
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

  private static void requireArguments(String[] args, int expected) {
    if (args.length != expected) {
      throw new IllegalArgumentException("Unexpected command arguments.");
    }
  }
}
