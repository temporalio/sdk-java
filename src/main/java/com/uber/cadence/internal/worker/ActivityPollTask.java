/*
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

package com.uber.cadence.internal.worker;

import com.uber.cadence.InternalServiceError;
import com.uber.cadence.PollForActivityTaskRequest;
import com.uber.cadence.PollForActivityTaskResponse;
import com.uber.cadence.ServiceBusyError;
import com.uber.cadence.TaskList;
import com.uber.cadence.TaskListMetadata;
import com.uber.cadence.internal.metrics.MetricsType;
import com.uber.cadence.serviceclient.IWorkflowService;
import com.uber.m3.tally.Stopwatch;
import com.uber.m3.util.Duration;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ActivityPollTask implements Poller.PollTask<ActivityWorker.MeasurableActivityTask> {

  private final IWorkflowService service;
  private final String domain;
  private final String taskList;
  private final SingleWorkerOptions options;
  private static final Logger log = LoggerFactory.getLogger(ActivityPollTask.class);

  public ActivityPollTask(
      IWorkflowService service, String domain, String taskList, SingleWorkerOptions options) {

    this.service = service;
    this.domain = domain;
    this.taskList = taskList;
    this.options = options;
  }

  @Override
  public ActivityWorker.MeasurableActivityTask poll() throws TException {
    options.getMetricsScope().counter(MetricsType.ACTIVITY_POLL_COUNTER).inc(1);
    Stopwatch sw = options.getMetricsScope().timer(MetricsType.ACTIVITY_POLL_LATENCY).start();
    Stopwatch e2eSW = options.getMetricsScope().timer(MetricsType.ACTIVITY_E2E_LATENCY).start();

    PollForActivityTaskRequest pollRequest = new PollForActivityTaskRequest();
    pollRequest.setDomain(domain);
    pollRequest.setIdentity(options.getIdentity());
    pollRequest.setTaskList(new TaskList().setName(taskList));

    if (options.getTaskListActivitiesPerSecond() > 0) {
      TaskListMetadata metadata = new TaskListMetadata();
      metadata.setMaxTasksPerSecond(options.getTaskListActivitiesPerSecond());
      pollRequest.setTaskListMetadata(metadata);
    }

    if (log.isDebugEnabled()) {
      log.debug("poll request begin: " + pollRequest);
    }
    PollForActivityTaskResponse result;
    try {
      result = service.PollForActivityTask(pollRequest);
    } catch (InternalServiceError | ServiceBusyError e) {
      options.getMetricsScope().counter(MetricsType.ACTIVITY_POLL_TRANSIENT_FAILED_COUNTER).inc(1);
      throw e;
    } catch (TException e) {
      options.getMetricsScope().counter(MetricsType.ACTIVITY_POLL_FAILED_COUNTER).inc(1);
      throw e;
    }

    if (result == null || result.getTaskToken() == null) {
      if (log.isDebugEnabled()) {
        log.debug("poll request returned no task");
      }
      options.getMetricsScope().counter(MetricsType.ACTIVITY_POLL_NO_TASK_COUNTER).inc(1);
      return null;
    }

    if (log.isTraceEnabled()) {
      log.trace("poll request returned " + result);
    }

    options.getMetricsScope().counter(MetricsType.ACTIVITY_POLL_SUCCEED_COUNTER).inc(1);
    options
        .getMetricsScope()
        .timer(MetricsType.ACTIVITY_SCHEDULED_TO_START_LATENCY)
        .record(
            Duration.ofNanos(
                result.getStartedTimestamp() - result.getScheduledTimestampOfThisAttempt()));
    sw.stop();
    return new ActivityWorker.MeasurableActivityTask(result, e2eSW);
  }
}
