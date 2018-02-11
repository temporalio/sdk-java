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
import com.uber.cadence.RespondQueryTaskCompletedRequest;
import com.uber.cadence.TaskList;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowExecutionStartedEventAttributes;
import com.uber.cadence.WorkflowQuery;
import com.uber.cadence.WorkflowService;
import com.uber.cadence.internal.common.WorkflowExecutionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.thrift.TException;

import java.lang.management.ManagementFactory;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

public class DecisionTaskPoller implements TaskPoller {

    private static final Log log = LogFactory.getLog(DecisionTaskPoller.class);

    private static final Log decisionsLog = LogFactory.getLog(DecisionTaskPoller.class.getName() + ".decisions");

    private static final int MAXIMUM_PAGE_SIZE = 500;

    private WorkflowService.Iface service;

    private String domain;

    private String taskListToPoll;

    private String identity;

    private boolean validated;

    private DecisionTaskHandler decisionTaskHandler;

    public DecisionTaskPoller() {
        identity = ManagementFactory.getRuntimeMXBean().getName();
    }

    public DecisionTaskPoller(WorkflowService.Iface service, String domain, String taskListToPoll,
                              DecisionTaskHandler decisionTaskHandler) {
        this.service = service;
        this.domain = domain;
        this.taskListToPoll = taskListToPoll;
        this.decisionTaskHandler = decisionTaskHandler;
        identity = ManagementFactory.getRuntimeMXBean().getName();
    }

    public String getIdentity() {
        return identity;
    }

    public void setIdentity(String identity) {
        validated = false;
        this.identity = identity;
    }

    public WorkflowService.Iface getService() {
        return service;
    }

    public String getDomain() {
        return domain;
    }

    public DecisionTaskHandler getDecisionTaskHandler() {
        return decisionTaskHandler;
    }

    public void setDecisionTaskHandler(DecisionTaskHandler decisionTaskHandler) {
        validated = false;
        this.decisionTaskHandler = decisionTaskHandler;
    }

    public void setService(WorkflowService.Iface service) {
        validated = false;
        this.service = service;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getTaskListToPoll() {
        return taskListToPoll;
    }

    public void setTaskListToPoll(String pollTaskList) {
        this.taskListToPoll = pollTaskList;
    }

    /**
     * Poll for a decision task.
     *
     * @return null if poll timed out
     * @throws TException
     */
    private PollForDecisionTaskResponse poll() throws TException {
        validate();
        PollForDecisionTaskRequest pollRequest = new PollForDecisionTaskRequest();

        pollRequest.setDomain(domain);
        pollRequest.setIdentity(identity);

        TaskList tl = new TaskList();
        tl.setName(taskListToPoll);
        pollRequest.setTaskList(tl);

        if (log.isDebugEnabled()) {
            log.debug("poll request begin: " + pollRequest);
        }
        PollForDecisionTaskResponse result = service.PollForDecisionTask(pollRequest);
        if (log.isDebugEnabled()) {
            log.debug("poll request returned decision task: workflowType=" + result.getWorkflowType() + ", workflowExecution="
                    + result.getWorkflowExecution() + ", startedEventId=" + result.getStartedEventId() + ", previousStartedEventId=" + result.getPreviousStartedEventId()
                    + (result.getQuery() != null ? "queryType=" + result.getQuery().getQueryType() : ""));
        }

        if (result == null || result.getTaskToken() == null) {
            return null;
        }
        return result;
    }

    /**
     * Poll for a workflow task and call appropriate decider. This method might
     * call the service multiple times to retrieve the whole history it it is
     * paginated.
     *
     * @return true if task was polled and decided upon, false if poll timed out
     * @throws Exception
     */
    @Override
    public boolean pollAndProcessSingleTask() throws Exception {
        Object taskCompletedRequest = null;
        PollForDecisionTaskResponse task = poll();
        if (task == null) {
            return false;
        }
        DecisionTaskWithHistoryIterator historyIterator = new DecisionTaskWithHistoryIteratorImpl(task);
        try {
            taskCompletedRequest = decisionTaskHandler.handleDecisionTask(historyIterator);
            if (taskCompletedRequest instanceof RespondDecisionTaskCompletedRequest) {
                RespondDecisionTaskCompletedRequest r = (RespondDecisionTaskCompletedRequest) taskCompletedRequest;
                if (decisionsLog.isTraceEnabled()) {
                    decisionsLog.trace(WorkflowExecutionUtils.prettyPrintDecisions(r.getDecisions()));
                }
                service.RespondDecisionTaskCompleted(r);
            } else if (taskCompletedRequest instanceof RespondQueryTaskCompletedRequest) {
                RespondQueryTaskCompletedRequest r = (RespondQueryTaskCompletedRequest) taskCompletedRequest;
                service.RespondQueryTaskCompleted(r);
            }
        } catch (Exception e) {
            PollForDecisionTaskResponse firstTask = historyIterator.getDecisionTask();
            if (firstTask != null) {
                if (log.isWarnEnabled()) {
                    log.warn("DecisionTask failure: taskId= " + firstTask.getStartedEventId() + ", workflowExecution="
                            + firstTask.getWorkflowExecution() + ", reply=" + taskCompletedRequest, e);
                }
            }
            if (taskCompletedRequest instanceof RespondDecisionTaskCompletedRequest && decisionsLog.isWarnEnabled()) {
                RespondDecisionTaskCompletedRequest r = (RespondDecisionTaskCompletedRequest) taskCompletedRequest;

                decisionsLog.warn("Failed taskId=" + firstTask.getStartedEventId() + " decisions="
                        + WorkflowExecutionUtils.prettyPrintDecisions(r.getDecisions()));
            }
            throw e;
        }
        return true;
    }

    public byte[] queryWorkflowExecution(WorkflowExecution execution, String queryType, byte[] args) throws Exception {
        PollForDecisionTaskResponse task = new PollForDecisionTaskResponse();
        Iterator<HistoryEvent> history = WorkflowExecutionUtils.getHistory(service, domain, execution);
        DecisionTaskWithHistoryIterator historyIterator = new ReplayDecisionTaskWithHistoryIterator(execution, history);
        WorkflowQuery query = new WorkflowQuery();
        query.setQueryType(queryType).setQueryArgs(args);
        historyIterator.getDecisionTask().setQuery(query);
        Object taskCompletedRequest = decisionTaskHandler.handleDecisionTask(historyIterator);
        if (taskCompletedRequest instanceof RespondQueryTaskCompletedRequest) {
            RespondQueryTaskCompletedRequest r = (RespondQueryTaskCompletedRequest) taskCompletedRequest;
            return r.getQueryResult();
        }
        throw new RuntimeException("Query returned wrong response: " + taskCompletedRequest);
    }

    private void validate() throws IllegalStateException {
        if (validated) {
            return;
        }
        checkFieldSet("decisionTaskHandler", decisionTaskHandler);
        checkFieldSet("service", service);
        checkFieldSet("identity", identity);
        validated = true;
    }

    private void checkFieldSet(String fieldName, Object fieldValue) throws IllegalStateException {
        if (fieldValue == null) {
            throw new IllegalStateException("Required field " + fieldName + " is not set");
        }
    }

    protected void checkFieldNotNegative(String fieldName, long fieldValue) throws IllegalStateException {
        if (fieldValue < 0) {
            throw new IllegalStateException("Field " + fieldName + " is negative");
        }
    }

    @Override
    public void shutdown() {
    }

    @Override
    public void shutdownNow() {
    }

    @Override
    public boolean awaitTermination(long left, TimeUnit milliseconds) throws InterruptedException {
        //TODO: Waiting for all currently running pollAndProcessSingleTask to complete 
        return false;
    }

    private class DecisionTaskWithHistoryIteratorImpl implements DecisionTaskWithHistoryIterator {
        private final PollForDecisionTaskResponse task;
        private Iterator<HistoryEvent> current;
        private byte[] nextPageToken;
        private WorkflowExecutionStartedEventAttributes workflowExecutionStartedEventAttributes;

        public DecisionTaskWithHistoryIteratorImpl(PollForDecisionTaskResponse task) {
            this.task = task;
            History history = task.getHistory();
            HistoryEvent firstEvent = history.getEvents().get(0);
            this.workflowExecutionStartedEventAttributes = firstEvent.getWorkflowExecutionStartedEventAttributes();
            if (this.workflowExecutionStartedEventAttributes == null) {
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
                    GetWorkflowExecutionHistoryRequest request = new GetWorkflowExecutionHistoryRequest();
                    request.setDomain(domain);
                    request.setExecution(task.getWorkflowExecution());
                    request.setMaximumPageSize(MAXIMUM_PAGE_SIZE);
                    try {
                        GetWorkflowExecutionHistoryResponse r = service.GetWorkflowExecutionHistory(request);
                        current = r.getHistory().getEventsIterator();
                        nextPageToken = r.getNextPageToken();
                    } catch (TException e) {
                        throw new RuntimeException(e);
                    }
                    return current.next();
                }
            };
        }

        @Override
        public WorkflowExecutionStartedEventAttributes getWorkflowExecutionStartedEventAttributes() {
            return workflowExecutionStartedEventAttributes;
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
        public WorkflowExecutionStartedEventAttributes getWorkflowExecutionStartedEventAttributes() {
            return startedEvent;
        }
    }
}
