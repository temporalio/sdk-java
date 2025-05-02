package io.temporal.internal.client;

import io.nexusrpc.Link;
import java.util.List;
import java.util.Map;

public final class NexusStartWorkflowRequest {
  private final String requestId;
  private final String callbackUrl;
  private final Map<String, String> callbackHeaders;
  private final String taskQueue;
  private final List<Link> links;

  public NexusStartWorkflowRequest(
      String requestId,
      String callbackUrl,
      Map<String, String> callbackHeaders,
      String taskQueue,
      List<Link> links) {
    this.requestId = requestId;
    this.callbackUrl = callbackUrl;
    this.callbackHeaders = callbackHeaders;
    this.taskQueue = taskQueue;
    this.links = links;
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
}
