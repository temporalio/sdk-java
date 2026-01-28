package io.temporal.internal.client;

import static io.temporal.serviceclient.MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY;

import com.google.common.base.Preconditions;
import com.google.protobuf.ByteString;
import com.uber.m3.tally.Scope;
import io.temporal.activity.ManualActivityCompletionClient;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatByIdRequest;
import io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatByIdResponse;
import io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatRequest;
import io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatResponse;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.Optional;
import javax.annotation.Nonnull;

/**
 * Contains methods that could but didn't become a part of the main {@link
 * ManualActivityCompletionClient}, because they are not intended to be called by our users
 * directly.
 */
public final class ActivityClientHelper {
  private ActivityClientHelper() {}

  public static RecordActivityTaskHeartbeatResponse sendHeartbeatRequest(
      WorkflowServiceStubs service,
      String namespace,
      String identity,
      byte[] taskToken,
      Optional<Payloads> payloads,
      Scope metricsScope) {
    RecordActivityTaskHeartbeatRequest.Builder request =
        RecordActivityTaskHeartbeatRequest.newBuilder()
            .setTaskToken(ByteString.copyFrom(taskToken))
            .setNamespace(namespace)
            .setIdentity(identity);
    payloads.ifPresent(request::setDetails);
    return service
        .blockingStub()
        .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
        .recordActivityTaskHeartbeat(request.build());
  }

  public static RecordActivityTaskHeartbeatByIdResponse recordActivityTaskHeartbeatById(
      WorkflowServiceStubs service,
      String namespace,
      String identity,
      WorkflowExecution execution,
      @Nonnull String activityId,
      Optional<Payloads> payloads,
      Scope metricsScope) {
    Preconditions.checkNotNull(activityId, "Either activity id or task token are required");
    RecordActivityTaskHeartbeatByIdRequest.Builder request =
        RecordActivityTaskHeartbeatByIdRequest.newBuilder()
            .setRunId(execution.getRunId())
            .setWorkflowId(execution.getWorkflowId())
            .setActivityId(activityId)
            .setNamespace(namespace)
            .setIdentity(identity);
    payloads.ifPresent(request::setDetails);
    return service
        .blockingStub()
        .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
        .recordActivityTaskHeartbeatById(request.build());
  }
}
