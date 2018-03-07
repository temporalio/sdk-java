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

package com.uber.cadence.internal.common;

import com.uber.cadence.*;
import com.uber.cadence.WorkflowService.Iface;
import com.uber.cadence.client.WorkflowTerminatedException;
import com.uber.cadence.client.WorkflowTimedOutException;
import com.uber.cadence.common.RetryOptions;
import com.uber.cadence.workflow.Workflow;
import org.apache.thrift.TException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Convenience methods to be used by unit tests and during development.
 *
 * @author fateev
 */
public class WorkflowExecutionUtils {

    private static RetryOptions retryParameters = new RetryOptions.Builder()
            .setBackoffCoefficient(2)
            .setInitialInterval(Duration.ofMillis(500))
            .setMaximumInterval(Duration.ofSeconds(30))
            .setDoNotRetry(BadRequestError.class, EntityNotExistsError.class)
            .build();

    /**
     * Returns result of a workflow instance execution or throws an exception if workflow did not complete successfully.
     *
     * @param workflowType is optional.
     * @throws TimeoutException                 if workflow didn't complete within specified timeout
     * @throws CancellationException            if workflow was cancelled
     * @throws WorkflowExecutionFailedException if workflow execution failed
     * @throws WorkflowTimedOutException        if workflow execution exceeded its execution timeout and was forcefully terminated by the Cadence server.
     * @throws WorkflowTerminatedException      if workflow execution was terminated through an external terminate command.
     */
    public static byte[] getWorkflowExecutionResult(Iface service, String domain, WorkflowExecution workflowExecution, String workflowType, long timeout, TimeUnit unit)
            throws TimeoutException, CancellationException, WorkflowExecutionFailedException, WorkflowTerminatedException, WorkflowTimedOutException {
        // getIntanceCloseEvent waits for workflow completion including new runs.
        HistoryEvent closeEvent = getInstanceCloseEvent(service, domain, workflowExecution, timeout, unit);
        if (closeEvent == null) {
            throw new IllegalStateException("Workflow is still running");
        }
        switch (closeEvent.getEventType()) {
            case WorkflowExecutionCompleted:
                return closeEvent.getWorkflowExecutionCompletedEventAttributes().getResult();
            case WorkflowExecutionCanceled:
                byte[] details = closeEvent.getWorkflowExecutionCanceledEventAttributes().getDetails();
                String message = details != null ? new String(details, StandardCharsets.UTF_8) : null;
                throw new CancellationException(message);
            case WorkflowExecutionFailed:
                WorkflowExecutionFailedEventAttributes failed = closeEvent.getWorkflowExecutionFailedEventAttributes();
                throw new WorkflowExecutionFailedException(failed.getReason(), failed.getDetails(), failed.getDecisionTaskCompletedEventId());
            case WorkflowExecutionTerminated:
                WorkflowExecutionTerminatedEventAttributes terminated = closeEvent.getWorkflowExecutionTerminatedEventAttributes();
                throw new WorkflowTerminatedException(workflowExecution, workflowType, terminated.getReason(), terminated.getIdentity(), terminated.getDetails());
            case WorkflowExecutionTimedOut:
                WorkflowExecutionTimedOutEventAttributes timedOut = closeEvent.getWorkflowExecutionTimedOutEventAttributes();
                throw new WorkflowTimedOutException(workflowExecution, workflowType, timedOut.getTimeoutType());
            default:
                throw new RuntimeException("Workflow end state is not completed: " + prettyPrintHistoryEvent(closeEvent));
        }
    }

    /**
     * Returns an instance closing event, potentially waiting for workflow to complete.
     */
    public static HistoryEvent getInstanceCloseEvent(Iface service, String domain,
                                                     WorkflowExecution workflowExecution, long timeout, TimeUnit unit) throws TimeoutException {
        byte[] pageToken = null;
        GetWorkflowExecutionHistoryResponse response;
        // TODO: Interrupt service long poll call on timeout and on interrupt
        long start = System.currentTimeMillis();
        HistoryEvent event;
        do {
            GetWorkflowExecutionHistoryRequest r = new GetWorkflowExecutionHistoryRequest();
            r.setDomain(domain);
            r.setExecution(workflowExecution);
            r.setHistoryEventFilterType(HistoryEventFilterType.CLOSE_EVENT);
            r.setNextPageToken(pageToken);
            try {
                response = SynchronousRetryer.retryWithResult(retryParameters,
                        () -> service.GetWorkflowExecutionHistory(r));
            } catch (TException e) {
                // TODO: Refactor to avoid this ugly circular dependency.
                throw Workflow.throwWrapped(e);
            }
            if (timeout != 0 && System.currentTimeMillis() - start > unit.toMillis(timeout)) {
                throw new TimeoutException("WorkflowId=" + workflowExecution.getWorkflowId() +
                        ", runId=" + workflowExecution.getRunId() + ", timeout=" + timeout + ", unit=" + unit);
            }
            pageToken = response.getNextPageToken();
            History history = response.getHistory();
            if (history != null && history.getEvents().size() > 0) {
                event = history.getEvents().get(0);
                if (!isWorkflowExecutionCompletedEvent(event)) {
                    throw new RuntimeException("Last history event is not completion event: " + event);
                }
                // Workflow called continueAsNew. Start polling the new generation with new runId.
                if (event.getEventType() == EventType.WorkflowExecutionContinuedAsNew) {
                    pageToken = null;
                    workflowExecution = new WorkflowExecution().setWorkflowId(workflowExecution.getWorkflowId()).
                            setRunId(event.getWorkflowExecutionContinuedAsNewEventAttributes().getNewExecutionRunId());
                    continue;
                }
                break;
            }
        } while (true);
        return event;
    }

    public static boolean isWorkflowExecutionCompletedEvent(HistoryEvent event) {
        return ((event != null) && event.getEventType() == EventType.WorkflowExecutionCompleted
                || event.getEventType() == EventType.WorkflowExecutionCanceled
                || event.getEventType() == EventType.WorkflowExecutionFailed
                || event.getEventType() == EventType.WorkflowExecutionTimedOut
                || event.getEventType() == EventType.WorkflowExecutionContinuedAsNew
                || event.getEventType() == EventType.WorkflowExecutionTerminated);
    }

    public static boolean isActivityTaskClosedEvent(HistoryEvent event) {
        return ((event != null) && (event.getEventType() == EventType.ActivityTaskCompleted
                || event.getEventType() == EventType.ActivityTaskCanceled
                || event.getEventType() == EventType.ActivityTaskFailed
                || event.getEventType() == EventType.ActivityTaskTimedOut));
    }

    public static boolean isExternalWorkflowClosedEvent(HistoryEvent event) {
        return ((event != null) && (event.getEventType() == EventType.ChildWorkflowExecutionCompleted
                || event.getEventType() == EventType.ChildWorkflowExecutionCanceled
                || event.getEventType() == EventType.ChildWorkflowExecutionFailed
                || event.getEventType() == EventType.ChildWorkflowExecutionTerminated
                || event.getEventType() == EventType.ChildWorkflowExecutionTimedOut));
    }

    public static WorkflowExecution getWorkflowIdFromExternalWorkflowCompletedEvent(HistoryEvent event) {
        if (event != null) {
            if (event.getEventType() == EventType.ChildWorkflowExecutionCompleted) {
                return event.getChildWorkflowExecutionCompletedEventAttributes().getWorkflowExecution();
            } else if (event.getEventType() == EventType.ChildWorkflowExecutionCanceled) {
                return event.getChildWorkflowExecutionCanceledEventAttributes().getWorkflowExecution();
            } else if (event.getEventType() == EventType.ChildWorkflowExecutionFailed) {
                return event.getChildWorkflowExecutionFailedEventAttributes().getWorkflowExecution();
            } else if (event.getEventType() == EventType.ChildWorkflowExecutionTerminated) {
                return event.getChildWorkflowExecutionTerminatedEventAttributes().getWorkflowExecution();
            } else if (event.getEventType() == EventType.ChildWorkflowExecutionTimedOut) {
                return event.getChildWorkflowExecutionTimedOutEventAttributes().getWorkflowExecution();
            }
        }

        return null;
    }

    public static String getId(HistoryEvent historyEvent) {
        String id = null;
        if (historyEvent != null) {
            if (historyEvent.getEventType() == EventType.StartChildWorkflowExecutionFailed) {
                id = historyEvent.getStartChildWorkflowExecutionFailedEventAttributes().getWorkflowId();
            }
        }

        return id;
    }

    public static String getFailureCause(HistoryEvent historyEvent) {
        String failureCause = null;
        if (historyEvent != null) {
            if (historyEvent.getEventType() == EventType.StartChildWorkflowExecutionFailed) {
                failureCause = historyEvent.getStartChildWorkflowExecutionFailedEventAttributes().getCause().toString();
//            } else if (historyEvent.getEventType() == EventType.SignalExternalWorkflowExecutionFailed) {
//                failureCause = historyEvent.getSignalExternalWorkflowExecutionFailedEventAttributes().getCause();
            } else {
                failureCause = "Cannot extract failure cause from " + historyEvent.getEventType();
            }
        }

        return failureCause;
    }

    /**
     * Blocks until workflow instance completes. <strong>Never</strong> use in
     * production setting as polling for worklow instance status is an expensive
     * operation.
     *
     * @param workflowExecution result of
     *                          {@link Iface#StartWorkflowExecution(StartWorkflowExecutionRequest)}
     * @return instance close status
     */
    public static WorkflowExecutionCloseStatus waitForWorkflowInstanceCompletion(Iface service, String domain,
                                                                                 WorkflowExecution workflowExecution) {
        try {
            return waitForWorkflowInstanceCompletion(service, domain, workflowExecution, 0, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new Error("should never happen", e);
        }
    }

    /**
     * Waits up to specified timeout for workflow instance completion.
     * <strong>Never</strong> use in production setting as polling for worklow
     * instance status is an expensive operation.
     *
     * @param workflowExecution result of
     *                          {@link Iface#StartWorkflowExecution(StartWorkflowExecutionRequest)}
     * @param timeout           maximum time to wait for completion. 0 means wait forever.
     * @return instance close status
     * @throws TimeoutException
     */
    public static WorkflowExecutionCloseStatus waitForWorkflowInstanceCompletion(Iface service, String domain,
                                                                                 WorkflowExecution workflowExecution, long timeout, TimeUnit unit) throws TimeoutException {
        HistoryEvent closeEvent = getInstanceCloseEvent(service, domain, workflowExecution, timeout, unit);
        return getCloseStatus(closeEvent);
    }

    private static WorkflowExecutionCloseStatus getCloseStatus(HistoryEvent event) {
        switch (event.getEventType()) {
            case WorkflowExecutionCanceled:
                return WorkflowExecutionCloseStatus.CANCELED;
            case WorkflowExecutionFailed:
                return WorkflowExecutionCloseStatus.FAILED;
            case WorkflowExecutionTimedOut:
                return WorkflowExecutionCloseStatus.TIMED_OUT;
            case WorkflowExecutionContinuedAsNew:
                return WorkflowExecutionCloseStatus.CONTINUED_AS_NEW;
            case WorkflowExecutionCompleted:
                return WorkflowExecutionCloseStatus.COMPLETED;
            case WorkflowExecutionTerminated:
                return WorkflowExecutionCloseStatus.TERMINATED;
            default:
                throw new IllegalArgumentException("Not close event: " + event);
        }
    }

    /**
     * Like
     * {@link #waitForWorkflowInstanceCompletion(Iface, String, WorkflowExecution, long, TimeUnit)}
     * , except will wait for continued generations of the original workflow
     * execution too.
     *
     * @see #waitForWorkflowInstanceCompletion(Iface, String, WorkflowExecution, long, TimeUnit)
     */
    public static WorkflowExecutionCloseStatus waitForWorkflowInstanceCompletionAcrossGenerations(
            Iface service, String domain, WorkflowExecution workflowExecution, long timeout, TimeUnit unit) throws InterruptedException, TimeoutException {

        WorkflowExecution lastExecutionToRun = workflowExecution;
        long millisecondsAtFirstWait = System.currentTimeMillis();
        WorkflowExecutionCloseStatus lastExecutionToRunCloseStatus =
                waitForWorkflowInstanceCompletion(service, domain, lastExecutionToRun, timeout, unit);

        // keep waiting if the instance continued as new
        while (lastExecutionToRunCloseStatus == WorkflowExecutionCloseStatus.CONTINUED_AS_NEW) {
            // get the new execution's information
            HistoryEvent closeEvent = getInstanceCloseEvent(service, domain, lastExecutionToRun, timeout, unit);
            WorkflowExecutionContinuedAsNewEventAttributes continuedAsNewAttributes =
                    closeEvent.getWorkflowExecutionContinuedAsNewEventAttributes();

            WorkflowExecution newGenerationExecution = new WorkflowExecution();
            newGenerationExecution.setRunId(continuedAsNewAttributes.getNewExecutionRunId());
            newGenerationExecution.setWorkflowId(lastExecutionToRun.getWorkflowId());

            // and wait for it
            long currentTime = System.currentTimeMillis();
            long millisecondsSinceFirstWait = currentTime - millisecondsAtFirstWait;
            long timeoutInSecondsForNextWait = unit.toMillis(timeout) - (millisecondsSinceFirstWait / 1000L);

            lastExecutionToRunCloseStatus = waitForWorkflowInstanceCompletion(
                    service, domain, newGenerationExecution, timeoutInSecondsForNextWait, TimeUnit.MILLISECONDS);
            lastExecutionToRun = newGenerationExecution;
        }

        return lastExecutionToRunCloseStatus;
    }

    /**
     * Like
     * {@link #waitForWorkflowInstanceCompletion(Iface, String, WorkflowExecution, long, TimeUnit)}
     * , but with no timeout.*
     */
    public static WorkflowExecutionCloseStatus waitForWorkflowInstanceCompletionAcrossGenerations(
            Iface service, String domain, WorkflowExecution workflowExecution) throws InterruptedException {
        try {
            return waitForWorkflowInstanceCompletionAcrossGenerations(
                    service, domain, workflowExecution, 0L, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new Error("should never happen", e);
        }
    }

    public static WorkflowExecutionInfo describeWorkflowInstance(Iface service, String domain,
                                                                 WorkflowExecution workflowExecution) {
        DescribeWorkflowExecutionRequest describeRequest = new DescribeWorkflowExecutionRequest();
        describeRequest.setDomain(domain);
        describeRequest.setExecution(workflowExecution);
        DescribeWorkflowExecutionResponse executionDetail = null;
        try {
            executionDetail = service.DescribeWorkflowExecution(describeRequest);
        } catch (TException e) {
            throw new RuntimeException(e);
        }
        WorkflowExecutionInfo instanceMetadata = executionDetail.getWorkflowExecutionInfo();
        return instanceMetadata;
    }

    /**
     * Returns workflow instance history in a human readable format.
     *
     * @param workflowExecution
     */
    public static String prettyPrintHistory(Iface service, String domain, WorkflowExecution workflowExecution) {
        return prettyPrintHistory(service, domain, workflowExecution, true);
    }

    /**
     * Returns workflow instance history in a human readable format.
     *
     * @param workflowExecution
     * @param showWorkflowTasks when set to false workflow task events (decider events) are
     *                          not included
     */
    public static String prettyPrintHistory(Iface service, String domain, WorkflowExecution workflowExecution,
                                            boolean showWorkflowTasks) {
        Iterator<HistoryEvent> events = getHistory(service, domain, workflowExecution);
        return prettyPrintHistory(events, showWorkflowTasks);
    }

    public static Iterator<HistoryEvent> getHistory(Iface service, String domain,
                                                    WorkflowExecution workflowExecution) {
        return new Iterator<HistoryEvent>() {
            byte[] nextPageToken;
            Iterator<HistoryEvent> current;

            {
                getNextPage();
            }

            @Override
            public boolean hasNext() {
                return current.hasNext() || nextPageToken != null;
            }

            @Override
            public HistoryEvent next() {
                if (current.hasNext()) {
                    return current.next();
                }
                getNextPage();
                return current.next();
            }

            private void getNextPage() {
                GetWorkflowExecutionHistoryResponse history = getHistoryPage(nextPageToken, service, domain, workflowExecution);
                current = history.getHistory().getEvents().iterator();
                nextPageToken = history.getNextPageToken();
            }

        };
    }

    public static GetWorkflowExecutionHistoryResponse getHistoryPage(byte[] nextPageToken, Iface service, String domain,
                                                                     WorkflowExecution workflowExecution) {

        GetWorkflowExecutionHistoryRequest getHistoryRequest = new GetWorkflowExecutionHistoryRequest();
        getHistoryRequest.setDomain(domain);
        getHistoryRequest.setExecution(workflowExecution);
        getHistoryRequest.setNextPageToken(nextPageToken);

        GetWorkflowExecutionHistoryResponse history;
        try {
            history = service.GetWorkflowExecutionHistory(getHistoryRequest);
        } catch (TException e) {
            throw new Error(e);
        }
        if (history == null) {
            throw new IllegalArgumentException("unknown workflow execution: " + workflowExecution);
        }
        return history;
    }

    /**
     * Returns workflow instance history in a human readable format.
     *
     * @param showWorkflowTasks when set to false workflow task events (decider events) are
     *                          not included
     * @history Workflow instance history
     */
    public static String prettyPrintHistory(History history, boolean showWorkflowTasks) {
        return prettyPrintHistory(history.getEvents().iterator(), showWorkflowTasks);
    }

    public static String prettyPrintHistory(Iterator<HistoryEvent> events, boolean showWorkflowTasks) {
        StringBuffer result = new StringBuffer();
        result.append("{");
        boolean first = true;
        while (events.hasNext()) {
            HistoryEvent event = events.next();
            if (!showWorkflowTasks && event.getEventType().toString().startsWith("WorkflowTask")) {
                continue;
            }
            if (first) {
                first = false;
            } else {
                result.append(",");
            }
            result.append("\n    ");
            result.append(prettyPrintHistoryEvent(event));
        }
        result.append("\n}");
        return result.toString();
    }

    public static String prettyPrintDecisions(Iterable<Decision> decisions) {
        StringBuffer result = new StringBuffer();
        result.append("{");
        boolean first = true;
        for (Decision decision : decisions) {
            if (first) {
                first = false;
            } else {
                result.append(",");
            }
            result.append("\n    ");
            result.append(prettyPrintDecision(decision));
        }
        result.append("\n}");
        return result.toString();
    }

    /**
     * Returns single event in a human readable format
     *
     * @param event event to pretty print
     */
    public static String prettyPrintHistoryEvent(HistoryEvent event) {
        String eventType = event.getEventType().toString();
        StringBuffer result = new StringBuffer();
        result.append(event.getEventId());
        result.append(": ");
        result.append(eventType);
        result.append(" ");
        result.append(prettyPrintObject(getEventAttributes(event), "getFieldValue", true, "    ", false, false));
        return result.toString();
    }

    private static Object getEventAttributes(HistoryEvent event) {
        try {
            Method m = HistoryEvent.class.getMethod("get" + event.getEventType() + "EventAttributes");
            return m.invoke(event);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            return event;
        }
    }

    /**
     * Returns single decision in a human readable format
     *
     * @param decision decision to pretty print
     */
    public static String prettyPrintDecision(Decision decision) {
        return prettyPrintObject(decision, "getFieldValue", true, "    ", true, true);
    }

    /**
     * Not really a generic method for printing random object graphs. But it
     * works for events and decisions.
     */
    private static String prettyPrintObject(Object object, String methodToSkip, boolean skipNullsAndEmptyCollections,
                                            String indentation, boolean skipLevel, boolean printTypeName) {
        StringBuffer result = new StringBuffer();
        if (object == null) {
            return "null";
        }
        Class<? extends Object> clz = object.getClass();
        if (Number.class.isAssignableFrom(clz)) {
            return String.valueOf(object);
        }
        if (Boolean.class.isAssignableFrom(clz)) {
            return String.valueOf(object);
        }
        if (clz.equals(String.class)) {
            return (String) object;
        }
        if (clz.equals(byte[].class)) {
            return new String((byte[]) object, StandardCharsets.UTF_8);
        }

        if (clz.equals(Date.class)) {
            return String.valueOf(object);
        }
        if (clz.equals(TaskList.class)) {
            return String.valueOf(((TaskList) object).getName());
        }
        if (clz.equals(ActivityType.class)) {
            return String.valueOf(((ActivityType) object).getName());
        }
        if (clz.equals(WorkflowType.class)) {
            return String.valueOf(((WorkflowType) object).getName());
        }

        if (Map.class.isAssignableFrom(clz)) {
            return String.valueOf(object);
        }
        if (Collection.class.isAssignableFrom(clz)) {
            return String.valueOf(object);
        }
        if (!skipLevel) {
            if (printTypeName) {
                result.append(object.getClass().getSimpleName());
                result.append(" ");
            }
            result.append("{");
        }
        Method[] eventMethods = object.getClass().getDeclaredMethods();
        boolean first = true;
        for (Method method : eventMethods) {
            String name = method.getName();
            if (!name.startsWith("get") ||
                    name.equals("getDecisionType") ||
                    method.getParameterCount() != 0 ||
                    !Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            if (name.equals(methodToSkip) || name.equals("getClass")) {
                continue;
            }
            if (Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            Object value;
            try {
                value = method.invoke(object, (Object[]) null);
            } catch (InvocationTargetException e) {
                throw new RuntimeException(e.getTargetException());
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            if (skipNullsAndEmptyCollections) {
                if (value == null) {
                    continue;
                }
                if (value instanceof Map && ((Map<?, ?>) value).isEmpty()) {
                    continue;
                }
                if (value instanceof Collection && ((Collection<?>) value).isEmpty()) {
                    continue;
                }
            }
            if (!skipLevel) {
                if (first) {
                    first = false;
                } else {
                    result.append(";");
                }
                result.append("\n");
                result.append(indentation);
                result.append("    ");
                result.append(name.substring(3));
                result.append(" = ");
                result.append(prettyPrintObject(value, methodToSkip, skipNullsAndEmptyCollections, indentation + "    ", false, false));
            } else {
                result.append(prettyPrintObject(value, methodToSkip, skipNullsAndEmptyCollections, indentation, false, printTypeName));
            }
        }
        if (!skipLevel) {
            result.append("\n");
            result.append(indentation);
            result.append("}");
        }
        return result.toString();
    }
}
