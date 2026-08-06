package io.temporal.releaseautomation;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface ReleaseOwnershipWorkflow {
  @WorkflowMethod
  void manage(OwnershipClaim initialClaim);

  @UpdateMethod
  OwnershipStatus claim(OwnershipClaim claim);

  @UpdateMethod
  OwnershipStatus recordManualMaven(ManualMavenAttempt attempt);

  @QueryMethod
  OwnershipStatus status();
}
