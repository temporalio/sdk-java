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

package io.temporal.serviceclient;

import io.grpc.CallOptions;
import io.grpc.MethodDescriptor;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;

class LongPollUtil {

  static <ReqT, RespT> boolean isLongPoll(
      MethodDescriptor<ReqT, RespT> method, CallOptions callOptions) {
    if (method == WorkflowServiceGrpc.getPollWorkflowTaskQueueMethod()
        || method == WorkflowServiceGrpc.getPollActivityTaskQueueMethod()
        || method == WorkflowServiceGrpc.getPollNexusTaskQueueMethod()
        || method == WorkflowServiceGrpc.getUpdateWorkflowExecutionMethod()
        || method == WorkflowServiceGrpc.getExecuteMultiOperationMethod()
        || method == WorkflowServiceGrpc.getPollWorkflowExecutionUpdateMethod()) {
      return true;
    }
    if (method == WorkflowServiceGrpc.getGetWorkflowExecutionHistoryMethod()) {
      Boolean longPoll = callOptions.getOption(MetricsTag.HISTORY_LONG_POLL_CALL_OPTIONS_KEY);
      return Boolean.TRUE.equals(longPoll);
    }
    return false;
  }

  private LongPollUtil() {}
}
