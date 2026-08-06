package io.temporal.releaseautomation;

import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;

public final class OwnershipActivitiesImpl implements OwnershipActivities {
  private final WorkflowClient client;

  public OwnershipActivitiesImpl(WorkflowClient client) {
    this.client = client;
  }

  @Override
  public OwnershipStatus claimTemporal(ReleaseIdentity release) {
    release.validate();
    return claim(client, OwnershipClaim.temporal(release));
  }

  @Override
  public OwnershipStatus handoffManual(ReleaseIdentity release, ControlEvidence evidence) {
    release.validate();
    evidence.validate();
    return claim(
        client,
        OwnershipClaim.manual(
            release.candidate.tag,
            release.candidate.commitSha,
            release.digest(),
            evidence.githubActor,
            evidence.githubRunId,
            true));
  }

  static OwnershipStatus claim(WorkflowClient client, OwnershipClaim claim) {
    claim.validate();
    ReleaseOwnershipWorkflow workflow = stub(client, claim.tag);
    WorkflowClient.start(workflow::manage, claim);
    return workflow.claim(claim);
  }

  static OwnershipStatus status(WorkflowClient client, String tag) {
    return client
        .newWorkflowStub(ReleaseOwnershipWorkflow.class, QueueNames.ownershipWorkflowId(tag))
        .status();
  }

  static ReleaseOwnershipWorkflow stub(WorkflowClient client, String tag) {
    return client.newWorkflowStub(
        ReleaseOwnershipWorkflow.class,
        WorkflowOptions.newBuilder()
            .setWorkflowId(QueueNames.ownershipWorkflowId(tag))
            .setTaskQueue(QueueNames.ownership(tag))
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
            .setWorkflowIdConflictPolicy(
                WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
            .build());
  }
}
