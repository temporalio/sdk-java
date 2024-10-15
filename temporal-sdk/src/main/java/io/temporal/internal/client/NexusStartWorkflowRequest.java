/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
