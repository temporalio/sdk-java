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
import java.util.Collections;
import java.util.List;

public final class CandidateWorkflowImpl implements CandidateWorkflow {
  static final String STATUS_MEMO_KEY = "CandidateStatus";
  private CandidateIdentity candidate;

  @Override
  public ReleaseIdentity prepare(CandidateIdentity candidateIdentity) {
    candidateIdentity.validate();
    candidate = candidateIdentity;
    List<String> pendingPlatforms = new ArrayList<>(ReleasePolicy.NATIVE_PLATFORMS);
    upsertStatus(pendingPlatforms, null);
    List<Promise<ArtifactEntry>> builds = new ArrayList<>();
    for (String platform : ReleasePolicy.NATIVE_PLATFORMS) {
      BuildActivities activities =
          Workflow.newActivityStub(
              BuildActivities.class,
              ActivityOptions.newBuilder()
                  .setTaskQueue(QueueNames.build(candidateIdentity, platform))
                  .setStartToCloseTimeout(Duration.ofMinutes(90))
                  .setHeartbeatTimeout(Duration.ofMinutes(1))
                  .setRetryOptions(
                      RetryOptions.newBuilder()
                          .setInitialInterval(Duration.ofSeconds(20))
                          .setMaximumInterval(Duration.ofMinutes(15))
                          .setDoNotRetry("ReleaseIdentityConflict")
                          .build())
                  .build());
      Promise<ArtifactEntry> build =
          Async.function(activities::buildAndStore, candidateIdentity, platform)
              .thenApply(
                  artifact -> {
                    pendingPlatforms.remove(platform);
                    upsertStatus(pendingPlatforms, null);
                    return artifact;
                  });
      builds.add(build);
    }

    Promise.allOf(builds).get();
    List<ArtifactEntry> artifacts = new ArrayList<>();
    for (Promise<ArtifactEntry> build : builds) {
      artifacts.add(build.get());
    }
    ReleaseIdentity identity =
        new ReleaseIdentity(
            candidateIdentity, new ArtifactManifest(artifacts), Workflow.getInfo().getRunId());
    CandidateStateActivities state =
        Workflow.newActivityStub(
            CandidateStateActivities.class,
            ActivityOptions.newBuilder()
                .setTaskQueue(QueueNames.candidateWorkflow(candidateIdentity))
                .setStartToCloseTimeout(Duration.ofMinutes(2))
                .setRetryOptions(
                    RetryOptions.newBuilder()
                        .setInitialInterval(Duration.ofSeconds(20))
                        .setMaximumInterval(Duration.ofMinutes(5))
                        .build())
                .build());
    if (state.manualReleaseComplete(candidateIdentity)) {
      return identity;
    }
    // Visibility can now discover and start a Worker for the child queue before the child's
    // first Workflow Task has written its own status memo.
    upsertStatus(pendingPlatforms, identity);
    ReleaseWorkflow child =
        Workflow.newChildWorkflowStub(
            ReleaseWorkflow.class,
            ChildWorkflowOptions.newBuilder()
                .setWorkflowId(QueueNames.releaseWorkflowId(identity))
                .setTaskQueue(QueueNames.releaseWorkflow(identity))
                .setMemo(Collections.singletonMap(ReleaseWorkflowImpl.IDENTITY_MEMO_KEY, identity))
                .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON)
                .build());
    Async.function(child::release, identity);
    Workflow.getWorkflowExecution(child).get();
    return identity;
  }

  @Override
  public CandidateIdentity candidate() {
    return candidate;
  }

  private void upsertStatus(List<String> pendingPlatforms, ReleaseIdentity releaseIdentity) {
    Workflow.upsertMemo(
        Collections.singletonMap(
            STATUS_MEMO_KEY, new CandidateStatus(candidate, pendingPlatforms, releaseIdentity)));
  }
}
