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

package io.temporal.client.schedules;

import com.uber.m3.tally.Scope;
import io.temporal.common.interceptors.ScheduleClientCallsInterceptor;
import io.temporal.internal.client.RootScheduleClientInvoker;
import io.temporal.internal.client.external.GenericWorkflowClient;
import io.temporal.internal.client.external.GenericWorkflowClientImpl;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.stream.Stream;

public final class ScheduleClientImpl implements ScheduleClient {
  private final WorkflowServiceStubs workflowServiceStubs;
  private final ScheduleClientOptions options;
  private final GenericWorkflowClient genericClient;
  private final Scope metricsScope;
  private final ScheduleClientCallsInterceptor scheduleClientCallsInvoker;

  public ScheduleClientImpl(
      WorkflowServiceStubs workflowServiceStubs, ScheduleClientOptions options) {
    this.workflowServiceStubs = workflowServiceStubs;
    this.options = options;
    this.metricsScope =
        workflowServiceStubs
            .getOptions()
            .getMetricsScope()
            .tagged(MetricsTag.defaultTags(options.getNamespace()));
    this.genericClient = new GenericWorkflowClientImpl(workflowServiceStubs, metricsScope);
    this.scheduleClientCallsInvoker = new RootScheduleClientInvoker(genericClient, options);
  }

  @Override
  public ScheduleHandle createSchedule(
      String scheduleID, Schedule schedule, ScheduleOptions options) {
    scheduleClientCallsInvoker.createSchedule(
        new ScheduleClientCallsInterceptor.CreateScheduleInput(scheduleID, schedule, options));
    return new ScheduleHandleImpl(scheduleClientCallsInvoker, scheduleID);
  }

  @Override
  public ScheduleHandle getHandle(String scheduleID) {
    return new ScheduleHandleImpl(scheduleClientCallsInvoker, scheduleID);
  }

  @Override
  public Stream<ScheduleListDescription> listSchedules(ScheduleListOptions options) {
    return scheduleClientCallsInvoker
        .listSchedules(new ScheduleClientCallsInterceptor.ListSchedulesInput(options.getPageSize()))
        .getStream();
  }
}
