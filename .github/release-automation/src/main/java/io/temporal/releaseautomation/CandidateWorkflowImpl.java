package io.temporal.releaseautomation;

import io.temporal.activity.ActivityOptions;
import io.temporal.api.enums.v1.ParentClosePolicy;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class CandidateWorkflowImpl implements CandidateWorkflow {
  private CandidateIdentity candidate;

  @Override
  public ReleaseIdentity prepare(CandidateIdentity candidateIdentity) {
    candidateIdentity.validate();
    candidate = candidateIdentity;
    List<Promise<ArtifactEntry>> builds = new ArrayList<>();
    for (String platform : ReleasePolicy.NATIVE_PLATFORMS) {
      BuildActivities activities =
          Workflow.newActivityStub(
              BuildActivities.class,
              ActivityOptions.newBuilder()
                  .setTaskQueue(QueueNames.build(candidateIdentity, platform))
                  .setStartToCloseTimeout(Duration.ofMinutes(90))
                  .setRetryOptions(
                      RetryOptions.newBuilder()
                          .setInitialInterval(Duration.ofSeconds(20))
                          .setMaximumInterval(Duration.ofMinutes(15))
                          .setDoNotRetry("ReleaseIdentityConflict")
                          .build())
                  .build());
      builds.add(Async.function(activities::buildAndStore, candidateIdentity, platform));
    }

    Promise.allOf(builds).get();
    List<ArtifactEntry> artifacts = new ArrayList<>();
    for (Promise<ArtifactEntry> build : builds) {
      artifacts.add(build.get());
    }
    ReleaseIdentity identity =
        new ReleaseIdentity(candidateIdentity, new ArtifactManifest(artifacts));
    ReleaseWorkflow child =
        Workflow.newChildWorkflowStub(
            ReleaseWorkflow.class,
            ChildWorkflowOptions.newBuilder()
                .setWorkflowId(QueueNames.releaseWorkflowId(identity))
                .setTaskQueue(QueueNames.releaseWorkflow(identity))
                .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON)
                .build());
    Async.function(child::release, identity);
    return identity;
  }

  @Override
  public CandidateIdentity candidate() {
    return candidate;
  }
}
