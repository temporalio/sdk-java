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
package com.uber.cadence.worker;

import com.uber.cadence.ActivityExecutionContext;
import com.uber.cadence.ActivityFailureException;
import com.uber.cadence.common.WorkflowExecutionUtils;
import com.uber.cadence.generic.ActivityImplementation;
import com.uber.cadence.generic.ActivityImplementationFactory;
import com.uber.cadence.ActivityType;
import com.uber.cadence.EntityNotExistsError;
import com.uber.cadence.PollForActivityTaskRequest;
import com.uber.cadence.PollForActivityTaskResponse;
import com.uber.cadence.RespondActivityTaskCanceledRequest;
import com.uber.cadence.RespondActivityTaskCompletedRequest;
import com.uber.cadence.RespondActivityTaskFailedRequest;
import com.uber.cadence.TaskList;
import com.uber.cadence.WorkflowService;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.thrift.TException;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

public class SynchronousActivityTaskPoller implements TaskPoller {

    private static final Log log = LogFactory.getLog(SynchronousActivityTaskPoller.class);

    private WorkflowService.Iface service;

    private String domain;

    private String taskListToPoll;

    private ActivityImplementationFactory activityImplementationFactory;

    private String identity;

    private SynchronousRetrier<TException> reportCompletionRetrier;

    private SynchronousRetrier<TException> reportFailureRetrier;

    private boolean initialized;

    public SynchronousActivityTaskPoller(WorkflowService.Iface service, String domain, String taskListToPoll,
            ActivityImplementationFactory activityImplementationFactory) {
        this();
        this.service = service;
        this.domain = domain;
        this.taskListToPoll = taskListToPoll;
        this.activityImplementationFactory = activityImplementationFactory;
        setReportCompletionRetryParameters(new ExponentialRetryParameters());
        setReportFailureRetryParameters(new ExponentialRetryParameters());
    }

    public SynchronousActivityTaskPoller() {
        identity = ManagementFactory.getRuntimeMXBean().getName();
        int length = Math.min(identity.length(), GenericWorker.MAX_IDENTITY_LENGTH);
        identity = identity.substring(0, length);
    }

    public WorkflowService.Iface getService() {
        return service;
    }

    public void setService(WorkflowService.Iface service) {
        this.service = service;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getPollTaskList() {
        return taskListToPoll;
    }

    public void setTaskListToPoll(String taskList) {
        this.taskListToPoll = taskList;
    }

    public ActivityImplementationFactory getActivityImplementationFactory() {
        return activityImplementationFactory;
    }

    public void setActivityImplementationFactory(ActivityImplementationFactory activityImplementationFactory) {
        this.activityImplementationFactory = activityImplementationFactory;
    }

    public String getIdentity() {
        return identity;
    }

    public void setIdentity(String identity) {
        this.identity = identity;
    }

    public ExponentialRetryParameters getReportCompletionRetryParameters() {
        return reportCompletionRetrier.getRetryParameters();
    }

    public void setReportCompletionRetryParameters(ExponentialRetryParameters reportCompletionRetryParameters) {
        this.reportCompletionRetrier = new SynchronousRetrier(reportCompletionRetryParameters, EntityNotExistsError.class);
    }

    public ExponentialRetryParameters getReportFailureRetryParameters() {
        return reportFailureRetrier.getRetryParameters();
    }

    public void setReportFailureRetryParameters(ExponentialRetryParameters reportFailureRetryParameters) {
        this.reportFailureRetrier = new SynchronousRetrier(reportFailureRetryParameters, EntityNotExistsError.class);
    }

    public String getTaskListToPoll() {
        return taskListToPoll;
    }

    /**
     * Poll for a task using {@link #getPollTimeoutInSeconds()}
     * 
     * @return null if poll timed out
     */
    public PollForActivityTaskResponse poll() throws TException {
        if (!initialized) {
            checkRequiredProperty(service, "service");
            checkRequiredProperty(domain, "domain");
            checkRequiredProperty(taskListToPoll, "taskListToPoll");
            initialized = true;
        }

        PollForActivityTaskRequest pollRequest = new PollForActivityTaskRequest();
        pollRequest.setDomain(domain);
        pollRequest.setIdentity(identity);
        TaskList taskList = new TaskList();
        taskList.setName(taskListToPoll);
        pollRequest.setTaskList(taskList);
        if (log.isDebugEnabled()) {
            log.debug("poll request begin: " + pollRequest);
        }
        PollForActivityTaskResponse result = service.PollForActivityTask(pollRequest);
        if (result == null || result.getTaskToken() == null) {
            if (log.isDebugEnabled()) {
                log.debug("poll request returned no task");
            }
            return null;
        }
        if (log.isTraceEnabled()) {
            log.trace("poll request returned " + result);
        }
        return result;
    }

    /**
     * Poll for a activity task and execute correspondent implementation.
     * 
     * @return true if task was polled and decided upon, false if poll timed out
     * @throws Exception
     */
    @Override
    public boolean pollAndProcessSingleTask() throws Exception {
        PollForActivityTaskResponse task = poll();
        if (task == null) {
            return false;
        }
        execute(task);
        return true;
    }

    protected void execute(final PollForActivityTaskResponse task) throws Exception {
        byte[] output = null;
        ActivityType activityType = task.getActivityType();
        try {
            ActivityExecutionContext context = new ActivityExecutionContextImpl(service, domain, task);
            ActivityImplementation activityImplementation = activityImplementationFactory.getActivityImplementation(activityType);
            if (activityImplementation == null) {
                throw new ActivityFailureException("Unknown activity type: " + activityType);
            }
            output = activityImplementation.execute(context);
            if (!activityImplementation.getExecutionOptions().isManualActivityCompletion()) {
                respondActivityTaskCompletedWithRetry(task.getTaskToken(), output);
            }
        }
        catch (CancellationException e) {
            respondActivityTaskCanceledWithRetry(task.getTaskToken(), null);
            return;
        }
        catch (ActivityFailureException e) {
            if (log.isErrorEnabled()) {
                log.error("Failure processing activity task with taskId=" + ", workflowGenerationId="
                        + task.getWorkflowExecution().getWorkflowId() + ", activity=" + activityType
                        + ", activityInstanceId=" + task.getActivityId(), e);
            }
            respondActivityTaskFailedWithRetry(task.getTaskToken(), e.getReason(), e.getDetails());
        }
        catch (Exception e) {
            if (log.isErrorEnabled()) {
                log.error("Failure processing activity task with taskId=" + ", workflowGenerationId="
                        + task.getWorkflowExecution().getWorkflowId() + ", activity=" + activityType
                        + ", activityInstanceId=" + task.getActivityId(), e);
            }
            String reason = e.getMessage();
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            byte[] details = sw.toString().getBytes(UTF8_CHARSET);
            respondActivityTaskFailedWithRetry(task.getTaskToken(), reason, details);
        }
    }

    protected void respondActivityTaskFailedWithRetry(final byte[] taskToken, final String reason, final byte[] details)
        throws TException {
        if (reportFailureRetrier == null) {
            respondActivityTaskFailed(taskToken, reason, details);
        }
        else {
            reportFailureRetrier.retry(() -> {
                    respondActivityTaskFailed(taskToken, reason, details);
            });
        }
    }

    protected void respondActivityTaskFailed(byte[] taskToken, String reason, byte[] details)
        throws TException {
        RespondActivityTaskFailedRequest failedResponse = new RespondActivityTaskFailedRequest();
        failedResponse.setTaskToken(taskToken);
        failedResponse.setReason(WorkflowExecutionUtils.truncateReason(reason));
        failedResponse.setDetails(details);
        service.RespondActivityTaskFailed(failedResponse);
    }

    protected void respondActivityTaskCanceledWithRetry(final byte[] taskToken, final byte[] details)
        throws TException {
        if (reportFailureRetrier == null) {
            respondActivityTaskCanceled(taskToken, details);
        }
        else {
            reportFailureRetrier.retry(() -> respondActivityTaskCanceled(taskToken, details));
        }
    }

    protected void respondActivityTaskCanceled(byte[] taskToken, byte[] details) throws TException {
        RespondActivityTaskCanceledRequest canceledResponse = new RespondActivityTaskCanceledRequest();
        canceledResponse.setTaskToken(taskToken);
        canceledResponse.setDetails(details);
        service.RespondActivityTaskCanceled(canceledResponse);
    }

    protected void respondActivityTaskCompletedWithRetry(final byte[] taskToken, final byte[] output)
        throws TException {
        if (reportCompletionRetrier == null) {
            respondActivityTaskCompleted(taskToken, output);
        }
        else {
            reportCompletionRetrier.retry(() -> respondActivityTaskCompleted(taskToken, output));
        }
    }

    protected void respondActivityTaskCompleted(byte[] taskToken, byte[] output) throws TException {
        RespondActivityTaskCompletedRequest completedReponse = new RespondActivityTaskCompletedRequest();
        completedReponse.setTaskToken(taskToken);
        completedReponse.setResult(output);
        service.RespondActivityTaskCompleted(completedReponse);
    }

    protected void checkRequiredProperty(Object value, String name) {
        if (value == null) {
            throw new IllegalStateException("required property " + name + " is not set");
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
        return true;
    }
}
