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

import com.uber.cadence.GetWorkflowExecutionHistoryRequest;
import com.uber.cadence.GetWorkflowExecutionHistoryResponse;
import com.uber.cadence.History;
import com.uber.cadence.HistoryEvent;
import com.uber.cadence.PollForDecisionTaskRequest;
import com.uber.cadence.PollForDecisionTaskResponse;
import com.uber.cadence.RespondDecisionTaskCompletedRequest;
import com.uber.cadence.RespondDecisionTaskFailedRequest;
import com.uber.cadence.RespondQueryTaskCompletedRequest;
import com.uber.cadence.TaskList;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowExecutionStartedEventAttributes;
import com.uber.cadence.WorkflowQuery;
import com.uber.cadence.WorkflowService;
import com.uber.cadence.common.RetryOptions;
import com.uber.cadence.internal.common.SynchronousRetryer;
import com.uber.cadence.internal.common.WorkflowExecutionUtils;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class WorkflowWorker implements SuspendableWorker {

    private static final Logger log = LoggerFactory.getLogger(WorkflowWorker.class);

    private static final String POLL_THREAD_NAME_PREFIX = "Poller taskList=";
    private static final int MAXIMUM_PAGE_SIZE = 10000;

    private Poller poller;
    private final DecisionTaskHandler handler;
    private final WorkflowService.Iface service;
    private final String domain;
    private final String taskList;
    private final SingleWorkerOptions options;

    public WorkflowWorker(WorkflowService.Iface service, String domain, String taskList,
                          SingleWorkerOptions options, DecisionTaskHandler handler) {
        Objects.requireNonNull(service);
        Objects.requireNonNull(domain);
        Objects.requireNonNull(taskList);
        this.service = service;
        this.domain = domain;
        this.taskList = taskList;
        this.options = options;
        this.handler = handler;
    }

    public void start() {
        if (handler.isAnyTypeSupported()) {
            PollerOptions pollerOptions = options.getPollerOptions();
            if (pollerOptions.getPollThreadNamePrefix() == null) {
                pollerOptions = new PollerOptions.Builder(pollerOptions)
                        .setPollThreadNamePrefix(POLL_THREAD_NAME_PREFIX + "\"" + taskList +
                                "\", domain=\"" + domain + "\", type=\"workflow\"")
                        .build();
            }
            Poller.ThrowingRunnable pollTask =
                    new PollTask<>(service, domain, taskList, options, new TaskHandlerImpl(handler));
            poller = new Poller(pollerOptions, pollTask);
            poller.start();
        }
    }

    public byte[] queryWorkflowExecution(WorkflowExecution execution, String queryType, byte[] args) throws Exception {
        Iterator<HistoryEvent> history = WorkflowExecutionUtils.getHistory(service, domain, execution);
        DecisionTaskWithHistoryIterator historyIterator = new ReplayDecisionTaskWithHistoryIterator(execution, history);
        WorkflowQuery query = new WorkflowQuery();
        query.setQueryType(queryType).setQueryArgs(args);
        historyIterator.getDecisionTask().setQuery(query);
        DecisionTaskHandler.Result result = handler.handleDecisionTask(historyIterator);
        if (result.getQueryCompleted() != null) {
            RespondQueryTaskCompletedRequest r = result.getQueryCompleted();
            return r.getQueryResult();
        }
        throw new RuntimeException("Query returned wrong response: " + result);
    }

    public void shutdown() {
        if (poller != null) {
            poller.shutdown();
        }
    }

    public void shutdownNow() {
        if (poller != null) {
            poller.shutdownNow();
        }
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        if (poller == null) {
            return true;
        }
        return poller.awaitTermination(timeout, unit);
    }

    public boolean shutdownAndAwaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        if (poller == null) {
            return true;
        }
        return poller.shutdownAndAwaitTermination(timeout, unit);
    }

    public boolean isRunning() {
        if (poller == null) {
            return false;
        }
        return poller.isRunning();
    }

    public void suspendPolling() {
        if (poller != null) {
            poller.suspendPolling();
        }
    }

    public void resumePolling() {
        if (poller != null) {
            poller.resumePolling();
        }
    }

    private class TaskHandlerImpl implements PollTask.TaskHandler<PollForDecisionTaskResponse> {

        final DecisionTaskHandler handler;

        private TaskHandlerImpl(DecisionTaskHandler handler) {
            this.handler = handler;
        }

        @Override
        public void handle(WorkflowService.Iface service, String domain, String taskList, PollForDecisionTaskResponse task) throws Exception {
            DecisionTaskHandler.Result response = handler.handleDecisionTask(new DecisionTaskWithHistoryIteratorImpl(task));
            sendReply(service, task.getTaskToken(), response);
        }

        @Override
        public PollForDecisionTaskResponse poll(WorkflowService.Iface service, String domain, String taskList) throws TException {
            PollForDecisionTaskRequest pollRequest = new PollForDecisionTaskRequest();

            pollRequest.setDomain(domain);
            pollRequest.setIdentity(options.getIdentity());

            TaskList tl = new TaskList();
            tl.setName(taskList);
            pollRequest.setTaskList(tl);

            if (log.isDebugEnabled()) {
                log.debug("poll request begin: " + pollRequest);
            }
            PollForDecisionTaskResponse result = service.PollForDecisionTask(pollRequest);
            if (log.isDebugEnabled()) {
                log.debug("poll request returned decision task: workflowType=" + result.getWorkflowType() + ", workflowExecution="
                        + result.getWorkflowExecution() + ", startedEventId=" + result.getStartedEventId() + ", previousStartedEventId=" + result.getPreviousStartedEventId()
                        + (result.getQuery() != null ? ", queryType=" + result.getQuery().getQueryType() : ""));
            }

            if (result == null || result.getTaskToken() == null) {
                return null;
            }
            return result;
        }

        @Override
        public Throwable wrapFailure(PollForDecisionTaskResponse task, Throwable failure) {
            WorkflowExecution execution = task.getWorkflowExecution();
            return new RuntimeException("Failure processing decision task. WorkflowID="
                    + execution.getWorkflowId() + ", RunID=" + execution.getRunId(), failure);
        }

        private void sendReply(WorkflowService.Iface service, byte[] taskToken, DecisionTaskHandler.Result response) throws TException {
            RetryOptions ro = response.getRequestRetryOptions();
            RespondDecisionTaskCompletedRequest taskCompleted = response.getTaskCompleted();
            if (taskCompleted != null) {
                ro = options.getReportCompletionRetryOptions().merge(ro);
                taskCompleted.setIdentity(options.getIdentity());
                taskCompleted.setTaskToken(taskToken);
                SynchronousRetryer.retry(ro,
                        () -> service.RespondDecisionTaskCompleted(taskCompleted));
            } else {
                RespondDecisionTaskFailedRequest taskFailed = response.getTaskFailed();
                if (taskFailed != null) {
                    ro = options.getReportFailureRetryOptions().merge(ro);
                    taskFailed.setIdentity(options.getIdentity());
                    taskFailed.setTaskToken(taskToken);
                    SynchronousRetryer.retry(ro,
                            () -> service.RespondDecisionTaskFailed(taskFailed));
                } else {
                    RespondQueryTaskCompletedRequest queryCompleted = response.getQueryCompleted();
                    if (queryCompleted != null) {
                        queryCompleted.setTaskToken(taskToken);
                        // Do not retry query response.
                        service.RespondQueryTaskCompleted(queryCompleted);
                    }
                }
            }
            // Manual activity completion
        }

    }

    private class DecisionTaskWithHistoryIteratorImpl implements DecisionTaskWithHistoryIterator {
        private long start = System.currentTimeMillis();
        private final PollForDecisionTaskResponse task;
        private Iterator<HistoryEvent> current;
        private byte[] nextPageToken;
        private WorkflowExecutionStartedEventAttributes startedEvent;

        DecisionTaskWithHistoryIteratorImpl(PollForDecisionTaskResponse task) {
            this.task = task;
            History history = task.getHistory();
            HistoryEvent firstEvent = history.getEvents().get(0);
            this.startedEvent = firstEvent.getWorkflowExecutionStartedEventAttributes();
            if (this.startedEvent == null) {
                throw new IllegalArgumentException("First event in the history is not WorkflowExecutionStarted");
            }
            current = history.getEventsIterator();
            nextPageToken = task.getNextPageToken();
        }

        @Override
        public PollForDecisionTaskResponse getDecisionTask() {
            return task;
        }

        @Override
        public Iterator<HistoryEvent> getHistory() {
            return new Iterator<HistoryEvent>() {
                @Override
                public boolean hasNext() {
                    return current.hasNext() || nextPageToken != null;
                }

                @Override
                public HistoryEvent next() {
                    if (current.hasNext()) {
                        return current.next();
                    }
                    Duration passed = Duration.ofMillis(System.currentTimeMillis() - start);
                    Duration timeout = Duration.ofSeconds(startedEvent.getTaskStartToCloseTimeoutSeconds());
                    Duration expiration = timeout.minus(passed);
                    if (expiration.isZero() || expiration.isNegative()) {
                        throw new Error("History pagination time exceeded TaskStartToCloseTimeout");
                    }
                    RetryOptions retryOptions = new RetryOptions.Builder()
                            .setExpiration(expiration)
                            .setInitialInterval(Duration.ofMillis(50))
                            .setMaximumInterval(Duration.ofSeconds(1))
                            .build();

                    GetWorkflowExecutionHistoryRequest request = new GetWorkflowExecutionHistoryRequest();
                    request.setDomain(domain);
                    request.setExecution(task.getWorkflowExecution());
                    request.setMaximumPageSize(MAXIMUM_PAGE_SIZE);
                    try {
                        GetWorkflowExecutionHistoryResponse r = SynchronousRetryer.retryWithResult(retryOptions,
                                () -> service.GetWorkflowExecutionHistory(request));
                        current = r.getHistory().getEventsIterator();
                        nextPageToken = r.getNextPageToken();
                    } catch (TException e) {
                        throw new Error(e);
                    }
                    return current.next();
                }
            };
        }

        @Override
        public WorkflowExecutionStartedEventAttributes getStartedEvent() {
            return startedEvent;
        }
    }

    private static class ReplayDecisionTaskWithHistoryIterator implements DecisionTaskWithHistoryIterator {

        private final Iterator<HistoryEvent> history;
        private final PollForDecisionTaskResponse task;
        private final WorkflowExecutionStartedEventAttributes startedEvent;
        private HistoryEvent first;

        private ReplayDecisionTaskWithHistoryIterator(WorkflowExecution execution, Iterator<HistoryEvent> history) {
            this.history = history;
            first = history.next();
            this.startedEvent = first.getWorkflowExecutionStartedEventAttributes();
            if (startedEvent == null) {
                throw new IllegalArgumentException("First history event is not WorkflowExecutionStarted, but: " + first.getEventType());
            }
            task = new PollForDecisionTaskResponse();
            task.setWorkflowExecution(execution);
            task.setStartedEventId(Long.MAX_VALUE);
            task.setPreviousStartedEventId(Long.MAX_VALUE);
            task.setWorkflowType(startedEvent.getWorkflowType());
        }

        @Override
        public PollForDecisionTaskResponse getDecisionTask() {
            return task;
        }

        @Override
        public Iterator<HistoryEvent> getHistory() {
            return new Iterator<HistoryEvent>() {
                @Override
                public boolean hasNext() {
                    return first != null || history.hasNext();
                }

                @Override
                public HistoryEvent next() {
                    if (first != null) {
                        HistoryEvent result = first;
                        first = null;
                        return result;
                    }
                    return history.next();
                }
            };
        }

        @Override
        public WorkflowExecutionStartedEventAttributes getStartedEvent() {
            return startedEvent;
        }
    }
}