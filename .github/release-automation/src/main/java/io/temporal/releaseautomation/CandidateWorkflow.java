package io.temporal.releaseautomation;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface CandidateWorkflow {
  @WorkflowMethod
  ReleaseIdentity prepare(CandidateIdentity candidate);

  @UpdateMethod
  CandidateStatus recordArtifact(String platform, GithubArtifactReceipt artifact);

  @QueryMethod
  CandidateIdentity candidate();
}
