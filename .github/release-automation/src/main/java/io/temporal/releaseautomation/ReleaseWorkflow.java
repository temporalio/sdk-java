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
  ReleaseStatus approve(ApprovalEvidence evidence);

  @QueryMethod
  ReleaseStatus status();
}
