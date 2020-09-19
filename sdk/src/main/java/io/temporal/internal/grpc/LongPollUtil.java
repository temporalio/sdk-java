/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.internal.grpc;

import io.grpc.CallOptions;
import io.grpc.MethodDescriptor;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.internal.metrics.MetricsTag;

class LongPollUtil {

  static <ReqT, RespT> boolean isLongPoll(
      MethodDescriptor<ReqT, RespT> method, CallOptions callOptions) {
    if (method == WorkflowServiceGrpc.getPollWorkflowTaskQueueMethod()
        || method == WorkflowServiceGrpc.getPollActivityTaskQueueMethod()) {
      return true;
    }
    if (method == WorkflowServiceGrpc.getGetWorkflowExecutionHistoryMethod()) {
      Boolean longPoll = callOptions.getOption(MetricsTag.HISTORY_LONG_POLL_CALL_OPTIONS_KEY);
      return longPoll != null && longPoll.booleanValue();
    }
    return false;
  }

  private LongPollUtil() {}
}
