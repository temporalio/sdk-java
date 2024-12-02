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

package io.temporal.internal.common;

import com.google.common.base.Defaults;
import io.temporal.api.common.v1.Callback;
import io.temporal.api.enums.v1.TaskQueueKind;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.internal.client.NexusStartWorkflowRequest;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Utility functions shared by the implementation code. */
public final class InternalUtils {
  private static final Logger log = LoggerFactory.getLogger(InternalUtils.class);

  public static TaskQueue createStickyTaskQueue(
      String stickyTaskQueueName, String normalTaskQueueName) {
    return TaskQueue.newBuilder()
        .setName(stickyTaskQueueName)
        .setKind(TaskQueueKind.TASK_QUEUE_KIND_STICKY)
        .setNormalName(normalTaskQueueName)
        .build();
  }

  public static TaskQueue createNormalTaskQueue(String taskQueueName) {
    return TaskQueue.newBuilder()
        .setName(taskQueueName)
        .setKind(TaskQueueKind.TASK_QUEUE_KIND_NORMAL)
        .build();
  }

  public static Object getValueOrDefault(Object value, Class<?> valueClass) {
    if (value != null) {
      return value;
    }
    return Defaults.defaultValue(valueClass);
  }

  /**
   * Creates a new stub that is bound to the same workflow as the given stub, but with the Nexus
   * callback URL and headers set.
   *
   * @param stub the stub to create a new stub from
   * @param request the request containing the Nexus callback URL and headers
   * @return a new stub bound to the same workflow as the given stub, but with the Nexus callback
   *     URL and headers set
   */
  public static WorkflowStub createNexusBoundStub(
      WorkflowStub stub, NexusStartWorkflowRequest request) {
    if (!stub.getOptions().isPresent()) {
      throw new IllegalArgumentException("Options are expected to be set on the stub");
    }
    WorkflowOptions options = stub.getOptions().get();
    if (options.getWorkflowId() == null) {
      throw new IllegalArgumentException(
          "WorkflowId is expected to be set on WorkflowOptions when used with Nexus");
    }
    WorkflowOptions.Builder nexusWorkflowOptions =
        WorkflowOptions.newBuilder(options)
            .setRequestId(request.getRequestId())
            .setCompletionCallbacks(
                Arrays.asList(
                    Callback.newBuilder()
                        .setNexus(
                            Callback.Nexus.newBuilder()
                                .setUrl(request.getCallbackUrl())
                                .putAllHeader(request.getCallbackHeaders())
                                .build())
                        .build()));
    if (options.getTaskQueue() == null) {
      nexusWorkflowOptions.setTaskQueue(request.getTaskQueue());
    }
    if (request.getLinks() != null) {
      nexusWorkflowOptions.setLinks(
          request.getLinks().stream()
              .map(
                  (link) -> {
                    if (io.temporal.api.common.v1.Link.WorkflowEvent.getDescriptor()
                        .getFullName()
                        .equals(link.getType())) {
                      io.temporal.api.nexus.v1.Link nexusLink =
                          io.temporal.api.nexus.v1.Link.newBuilder()
                              .setType(link.getType())
                              .setUrl(link.getUri().toString())
                              .build();
                      return LinkConverter.nexusLinkToWorkflowEvent(nexusLink);
                    } else {
                      log.warn("ignoring unsupported link data type: {}", link.getType());
                      return null;
                    }
                  })
              .filter(link -> link != null)
              .collect(Collectors.toList()));
    }
    return stub.newInstance(nexusWorkflowOptions.build());
  }

  /** Prohibit instantiation */
  private InternalUtils() {}
}
