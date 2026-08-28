package io.temporal.internal.client;

import static io.temporal.serviceclient.MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY;

import com.uber.m3.tally.Scope;
import io.temporal.activity.ManualActivityCompletionClient;
import io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatByIdRequest;
import io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatByIdResponse;
import io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatRequest;
import io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatResponse;
import io.temporal.serviceclient.WorkflowServiceStubs;

/**
 * Contains methods that could but didn't become a part of the main {@link
 * ManualActivityCompletionClient}, because they are not intended to be called by our users
 * directly.
 */
public final class ActivityClientHelper {
  private ActivityClientHelper() {}

  public static RecordActivityTaskHeartbeatResponse sendHeartbeatRequest(
      WorkflowServiceStubs service,
      RecordActivityTaskHeartbeatRequest request,
      Scope metricsScope) {
    return service
        .blockingStub()
        .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
        .recordActivityTaskHeartbeat(request);
  }

  public static RecordActivityTaskHeartbeatByIdResponse recordActivityTaskHeartbeatById(
      WorkflowServiceStubs service,
      RecordActivityTaskHeartbeatByIdRequest request,
      Scope metricsScope) {
    return service
        .blockingStub()
        .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
        .recordActivityTaskHeartbeatById(request);
  }
}
