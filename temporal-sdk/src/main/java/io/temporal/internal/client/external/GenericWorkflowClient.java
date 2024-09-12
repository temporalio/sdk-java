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

package io.temporal.internal.client.external;

import io.grpc.Deadline;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.common.Experimental;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;

public interface GenericWorkflowClient {

  StartWorkflowExecutionResponse start(StartWorkflowExecutionRequest request);

  void signal(SignalWorkflowExecutionRequest request);

  SignalWithStartWorkflowExecutionResponse signalWithStart(
      SignalWithStartWorkflowExecutionRequest request);

  void requestCancel(RequestCancelWorkflowExecutionRequest parameters);

  QueryWorkflowResponse query(QueryWorkflowRequest queryParameters);

  @Experimental
  UpdateWorkflowExecutionResponse update(
      @Nonnull UpdateWorkflowExecutionRequest updateParameters, @Nonnull Deadline deadline);

  @Experimental
  CompletableFuture<PollWorkflowExecutionUpdateResponse> pollUpdateAsync(
      @Nonnull PollWorkflowExecutionUpdateRequest request, @Nonnull Deadline deadline);

  void terminate(TerminateWorkflowExecutionRequest request);

  GetWorkflowExecutionHistoryResponse longPollHistory(
      @Nonnull GetWorkflowExecutionHistoryRequest request, @Nonnull Deadline deadline);

  CompletableFuture<GetWorkflowExecutionHistoryResponse> longPollHistoryAsync(
      @Nonnull GetWorkflowExecutionHistoryRequest request, @Nonnull Deadline deadline);

  GetWorkflowExecutionHistoryResponse getWorkflowExecutionHistory(
      @Nonnull GetWorkflowExecutionHistoryRequest request);

  CompletableFuture<GetWorkflowExecutionHistoryResponse> getWorkflowExecutionHistoryAsync(
      @Nonnull GetWorkflowExecutionHistoryRequest request);

  ListWorkflowExecutionsResponse listWorkflowExecutions(ListWorkflowExecutionsRequest listRequest);

  CompletableFuture<ListWorkflowExecutionsResponse> listWorkflowExecutionsAsync(
      ListWorkflowExecutionsRequest listRequest);

  CreateScheduleResponse createSchedule(CreateScheduleRequest request);

  CompletableFuture<ListSchedulesResponse> listSchedulesAsync(ListSchedulesRequest request);

  UpdateScheduleResponse updateSchedule(UpdateScheduleRequest request);

  PatchScheduleResponse patchSchedule(PatchScheduleRequest request);

  DeleteScheduleResponse deleteSchedule(DeleteScheduleRequest request);

  DescribeScheduleResponse describeSchedule(DescribeScheduleRequest request);

  @Experimental
  UpdateWorkerBuildIdCompatibilityResponse updateWorkerBuildIdCompatability(
      UpdateWorkerBuildIdCompatibilityRequest request);

  @Experimental
  ExecuteMultiOperationResponse executeMultiOperation(ExecuteMultiOperationRequest request);

  @Experimental
  GetWorkerBuildIdCompatibilityResponse getWorkerBuildIdCompatability(
      GetWorkerBuildIdCompatibilityRequest req);

  @Experimental
  GetWorkerTaskReachabilityResponse GetWorkerTaskReachability(GetWorkerTaskReachabilityRequest req);
}
