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
  String commitSha;
  String notesSha256;
  String manifestSha256;
  String releaseDigest;
  String approvalRunId;
  String approvalActor;

  DiscoveryJob(String role, String taskQueue) {
    this.role = role;
    this.taskQueue = taskQueue;
    this.runner = "ubuntu-latest";
    this.distribution = "temurin";
  }
}
