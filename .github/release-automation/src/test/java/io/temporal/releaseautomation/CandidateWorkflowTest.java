package io.temporal.releaseautomation;

import static org.junit.Assert.assertEquals;

import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.Test;

public class CandidateWorkflowTest {
  @Test
  public void parentCompletesAfterChildStartWithoutAReleaseWorker() {
    CandidateIdentity candidate = ReleaseFixtures.candidate();
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      Worker candidateWorker = environment.newWorker(QueueNames.candidateWorkflow(candidate));
      candidateWorker.registerWorkflowImplementationTypes(CandidateWorkflowImpl.class);
      candidateWorker.registerActivitiesImplementations(
          (CandidateStateActivities) ignored -> false);
      for (String platform : ReleasePolicy.NATIVE_PLATFORMS) {
        Worker buildWorker = environment.newWorker(QueueNames.build(candidate, platform));
        buildWorker.registerActivitiesImplementations(
            (BuildActivities)
                (ignored, requestedPlatform) -> {
                  String name =
                      ReleasePolicy.nativeArtifactName(candidate.version, requestedPlatform);
                  return new ArtifactEntry(
                      name,
                      String.format(
                          "%064x", ReleasePolicy.NATIVE_PLATFORMS.indexOf(requestedPlatform) + 1),
                      1002 + ReleasePolicy.NATIVE_PLATFORMS.indexOf(requestedPlatform),
                      "sdk-java/" + candidate.digest() + "/" + name);
                });
      }
      ReleaseIdentity expected = ReleaseFixtures.release();
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
      ReleaseIdentity actual = workflow.prepare(candidate);
      assertEquals(expected.digest(), actual.digest());
    }
  }
}
