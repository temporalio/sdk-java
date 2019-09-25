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
import com.uber.cadence.PollForDecisionTaskRequest;
import com.uber.cadence.PollForDecisionTaskResponse;
import com.uber.cadence.ServiceBusyError;
import com.uber.cadence.TaskList;
import com.uber.cadence.internal.metrics.MetricsType;
import com.uber.cadence.serviceclient.IWorkflowService;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.Stopwatch;
import com.uber.m3.util.Duration;
import java.util.Objects;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class WorkflowPollTask implements Poller.PollTask<PollForDecisionTaskResponse> {

  private final Scope metricScope;
  private final IWorkflowService service;
  private final String domain;
  private final String taskList;
  private final String identity;
  private static final Logger log = LoggerFactory.getLogger(WorkflowWorker.class);

  WorkflowPollTask(
      IWorkflowService service,
      String domain,
      String taskList,
      Scope metricScope,
      String identity) {
    this.identity = Objects.requireNonNull(identity);
    this.service = Objects.requireNonNull(service);
    this.domain = Objects.requireNonNull(domain);
    this.taskList = Objects.requireNonNull(taskList);
    this.metricScope = Objects.requireNonNull(metricScope);
  }

  @Override
  public PollForDecisionTaskResponse poll() throws TException {
    metricScope.counter(MetricsType.DECISION_POLL_COUNTER).inc(1);
    Stopwatch sw = metricScope.timer(MetricsType.DECISION_POLL_LATENCY).start();

    PollForDecisionTaskRequest pollRequest = new PollForDecisionTaskRequest();
    pollRequest.setDomain(domain);
    pollRequest.setIdentity(identity);

    TaskList tl = new TaskList();
    tl.setName(taskList);
    pollRequest.setTaskList(tl);

    if (log.isDebugEnabled()) {
      log.debug("poll request begin: " + pollRequest);
    }
    PollForDecisionTaskResponse result;
    try {
      result = service.PollForDecisionTask(pollRequest);
    } catch (InternalServiceError | ServiceBusyError e) {
      metricScope.counter(MetricsType.DECISION_POLL_TRANSIENT_FAILED_COUNTER).inc(1);
      throw e;
    } catch (TException e) {
      metricScope.counter(MetricsType.DECISION_POLL_FAILED_COUNTER).inc(1);
      throw e;
    }

    if (log.isDebugEnabled()) {
      log.debug(
          "poll request returned decision task: workflowType="
              + result.getWorkflowType()
              + ", workflowExecution="
              + result.getWorkflowExecution()
              + ", startedEventId="
              + result.getStartedEventId()
              + ", previousStartedEventId="
              + result.getPreviousStartedEventId()
              + (result.getQuery() != null
                  ? ", queryType=" + result.getQuery().getQueryType()
                  : ""));
    }

    if (result == null || result.getTaskToken() == null) {
      metricScope.counter(MetricsType.DECISION_POLL_NO_TASK_COUNTER).inc(1);
      return null;
    }

    metricScope.counter(MetricsType.DECISION_POLL_SUCCEED_COUNTER).inc(1);
    metricScope
        .timer(MetricsType.DECISION_SCHEDULED_TO_START_LATENCY)
        .record(Duration.ofNanos(result.getStartedTimestamp() - result.getScheduledTimestamp()));
    sw.stop();
    return result;
  }
}
