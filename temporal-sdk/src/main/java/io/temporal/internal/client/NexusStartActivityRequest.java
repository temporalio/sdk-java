package io.temporal.internal.client;

import io.nexusrpc.Link;
import io.temporal.client.StartActivityOptions;
import io.temporal.common.Experimental;
import io.temporal.common.interceptors.Header;
import java.util.List;
import java.util.Map;

/**
 * Request used to start an activity from a Nexus operation handler. Mirrors {@link
 * NexusStartWorkflowRequest} but carries the activity-specific scheduling payload.
 */
@Experimental
public final class NexusStartActivityRequest {
  private final String requestId;
  private final String callbackUrl;
  private final Map<String, String> callbackHeaders;
  private final String taskQueue;
  private final List<Link> links;
  private final String activityType;
  private final List<Object> args;
  private final StartActivityOptions options;
  private final Header header;

  public NexusStartActivityRequest(
      String requestId,
      String callbackUrl,
      Map<String, String> callbackHeaders,
      String taskQueue,
      List<Link> links,
      String activityType,
      List<Object> args,
      StartActivityOptions options,
      Header header) {
    this.requestId = requestId;
    this.callbackUrl = callbackUrl;
    this.callbackHeaders = callbackHeaders;
    this.taskQueue = taskQueue;
    this.links = links;
    this.activityType = activityType;
    this.args = args;
    this.options = options;
    this.header = header;
  }

  public String getRequestId() {
    return requestId;
  }

  public String getCallbackUrl() {
    return callbackUrl;
  }

  public Map<String, String> getCallbackHeaders() {
    return callbackHeaders;
  }

  public String getTaskQueue() {
    return taskQueue;
  }

  public List<Link> getLinks() {
    return links;
  }

  public String getActivityType() {
    return activityType;
  }

  public List<Object> getArgs() {
    return args;
  }

  public StartActivityOptions getOptions() {
    return options;
  }

  public Header getHeader() {
    return header;
  }
}
