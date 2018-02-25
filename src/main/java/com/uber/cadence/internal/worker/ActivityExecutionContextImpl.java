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

import com.uber.cadence.BadRequestError;
import com.uber.cadence.EntityNotExistsError;
import com.uber.cadence.PollForActivityTaskResponse;
import com.uber.cadence.RecordActivityTaskHeartbeatRequest;
import com.uber.cadence.RecordActivityTaskHeartbeatResponse;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowService;
import com.uber.cadence.WorkflowService.Iface;
import com.uber.cadence.activity.ActivityTask;
import com.uber.cadence.client.ActivityCancelledException;
import com.uber.cadence.client.ActivityCompletionException;
import com.uber.cadence.client.ActivityCompletionFailureException;
import com.uber.cadence.client.ActivityNotExistsException;
import com.uber.cadence.converter.DataConverter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.thrift.TException;

import java.util.concurrent.CancellationException;

/**
 * Base implementation of an {@link ActivityExecutionContext}.
 *
 * @author fateev, suskin
 * @see ActivityExecutionContext
 */
class ActivityExecutionContextImpl implements ActivityExecutionContext {

    private static final Log log = LogFactory.getLog(ActivityExecutionContextImpl.class);

    private final Iface service;

    private final String domain;

    private final ActivityTaskImpl task;
    private final DataConverter dataConverter;

    /**
     * Create an ActivityExecutionContextImpl with the given attributes.
     *
     * @param service  The {@link WorkflowService.Iface} this
     *                 ActivityExecutionContextImpl will send service calls to.
     * @param response The {@link PollForActivityTaskResponse} this ActivityExecutionContextImpl
     *                 will be used for.
     * @see ActivityExecutionContext
     */
    ActivityExecutionContextImpl(Iface service, String domain, PollForActivityTaskResponse response, DataConverter dataConverter) {
        this.domain = domain;
        this.service = service;
        this.task = new ActivityTaskImpl(response);
        this.dataConverter = dataConverter;
    }

    /**
     * @throws CancellationException
     * @see ActivityExecutionContext#recordActivityHeartbeat(Object)
     */
    @Override
    public void recordActivityHeartbeat(Object details) throws ActivityCompletionException {
        //TODO: call service with the specified minimal interval (through @ActivityExecutionOptions)
        // allowing more frequent calls of this method.
        RecordActivityTaskHeartbeatRequest r = new RecordActivityTaskHeartbeatRequest();
        r.setTaskToken(task.getTaskToken());
        byte[] serialized = dataConverter.toData(details);
        r.setDetails(serialized);
        RecordActivityTaskHeartbeatResponse status;
        try {
            status = service.RecordActivityTaskHeartbeat(r);
            if (status.isCancelRequested()) {
                throw new ActivityCancelledException(task);
            }
        } catch (EntityNotExistsError e) {
            throw new ActivityNotExistsException(task, e);
        } catch (BadRequestError e) {
            throw new ActivityCompletionFailureException(task, e);
        } catch (TException e) {
            log.warn("Failure heartbeating on activityID=" + task.getActivityId()
                    + " of Workflow=" + task.getWorkflowExecution(), e);
            // Not rethrowing to not fail activity implementation on intermittent connection or Cadence errors.
        }
    }

    /**
     * @see ActivityExecutionContext#getTask()
     */
    @Override
    public ActivityTask getTask() {
        return task;
    }

    /**
     * @see ActivityExecutionContext#getService()
     */
    @Override
    public Iface getService() {
        return service;
    }

    @Override
    public byte[] getTaskToken() {
        return task.getTaskToken();
    }

    @Override
    public WorkflowExecution getWorkflowExecution() {
        return task.getWorkflowExecution();
    }

    @Override
    public String getDomain() {
        return domain;
    }

}
