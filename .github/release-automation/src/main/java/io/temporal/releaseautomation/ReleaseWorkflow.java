package io.temporal.releaseautomation;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface ReleaseWorkflow {
  @WorkflowMethod
  ReleaseResult release(ReleaseIdentity identity);

  @UpdateMethod
  ReleaseStatus requestApproval(ApprovalRequest request);

  @UpdateMethod
  ReleaseStatus approve(ApprovalEvidence evidence);

  @UpdateMethod
  ReleaseStatus recordMavenPayload(GithubArtifactReceipt artifact);

  @UpdateMethod
  ReleaseStatus control(ControlEvidence evidence);

  @QueryMethod
  ReleaseStatus status();
}
