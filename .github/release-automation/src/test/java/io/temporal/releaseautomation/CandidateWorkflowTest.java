package io.temporal.releaseautomation;

import static org.junit.Assert.assertEquals;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.Test;

public class CandidateWorkflowTest {
  @Test
  public void recordsExactGithubArtifactsThenStartsTheReleaseChild() {
    CandidateIdentity candidate = ReleaseFixtures.candidate();
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      Worker candidateWorker = environment.newWorker(QueueNames.candidateWorkflow(candidate));
      candidateWorker.registerWorkflowImplementationTypes(CandidateWorkflowImpl.class);
      environment.start();

      CandidateWorkflow workflow =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  CandidateWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId(QueueNames.candidateWorkflowId(candidate))
                      .setTaskQueue(QueueNames.candidateWorkflow(candidate))
                      .build());
      WorkflowClient.start(workflow::prepare, candidate);
      int index = 1;
      for (String platform : ReleasePolicy.NATIVE_PLATFORMS) {
        String file = ReleasePolicy.nativeArtifactName(candidate.version(), platform);
        workflow.recordArtifact(
            platform,
            ReleaseFixtures.artifact(
                ReleasePolicy.githubNativeArtifactName(candidate, platform),
                file,
                index,
                1001 + index));
        index++;
      }
      ReleaseIdentity actual = WorkflowStub.fromTyped(workflow).getResult(ReleaseIdentity.class);
      assertEquals(ReleaseFixtures.release().digest(), actual.digest());
    }
  }
}
