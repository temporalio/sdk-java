package io.temporal.releaseautomation;

final class DiscoveryJob {
  String role;
  String runner;
  String distribution;
  String taskQueue;
  String platform;
  String workflowId;
  String runId;
  String tag;
  String version;
  String commitSha;
  String notesSha256;
  String manifestSha256;
  String releaseDigest;
  String candidateDigest;
  String candidateRunId;
  String approvalIssueNumber;
  String approvalIssueNodeId;
  String approvalIssueBodySha256;
  String automationCommit;
  String javaVersion;

  DiscoveryJob(String role, String taskQueue) {
    this.role = role;
    this.taskQueue = taskQueue;
    this.runner = "ubuntu-latest";
    this.distribution = "temurin";
    this.javaVersion = "17";
  }
}
