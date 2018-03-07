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

package com.uber.cadence.client;

import com.uber.cadence.WorkflowIdReusePolicy;
import com.uber.cadence.workflow.WorkflowMethod;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;
import java.time.Duration;

public class WorkflowOptionsTest {

    @WorkflowMethod
    public void defaultWorkflowOptions() {
    }

    @Test
    public void testOnlyOptionsPresent() throws NoSuchMethodException {
        WorkflowOptions o = new WorkflowOptions.Builder()
                .setTaskList("foo")
                .setExecutionStartToCloseTimeout(Duration.ofSeconds(321))
                .setTaskStartToCloseTimeout(Duration.ofSeconds(45))
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.AllowDuplicate)
                .build();
        WorkflowMethod a = WorkflowOptionsTest.class
                .getMethod("defaultWorkflowOptions")
                .getAnnotation(WorkflowMethod.class);
        Assert.assertEquals(o, WorkflowOptions.merge(a, o));
    }

    @Test
    public void testOnlyOptionsAndEmptyAnnotationsPresent() throws NoSuchMethodException {
        WorkflowOptions o = new WorkflowOptions.Builder()
                .setTaskList("foo")
                .setExecutionStartToCloseTimeout(Duration.ofSeconds(321))
                .setTaskStartToCloseTimeout(Duration.ofSeconds(13))
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.RejectDuplicate)
                .build();
        WorkflowMethod a = WorkflowOptionsTest.class
                .getMethod("defaultWorkflowOptions")
                .getAnnotation(WorkflowMethod.class);
        Assert.assertEquals(o, WorkflowOptions.merge(a, o));
    }

    @WorkflowMethod(executionStartToCloseTimeoutSeconds = 1135, taskList = "bar",
            taskStartToCloseTimeoutSeconds = 34,
            workflowId = "foo", workflowIdReusePolicy = WorkflowIdReusePolicy.AllowDuplicate)
    public void workflowOptions() {
    }

    @Test
    public void testOnlyAnnotationsPresent() throws NoSuchMethodException {
        Method method = WorkflowOptionsTest.class
                .getMethod("workflowOptions");
        WorkflowMethod a = method
                .getAnnotation(WorkflowMethod.class);
        WorkflowOptions o = new WorkflowOptions.Builder().build();
        WorkflowOptions merged = WorkflowOptions.merge(a, o);
        Assert.assertEquals(a.taskList(), merged.getTaskList());
        Assert.assertEquals(a.executionStartToCloseTimeoutSeconds(), merged.getExecutionStartToCloseTimeout().getSeconds());
        Assert.assertEquals(a.taskStartToCloseTimeoutSeconds(), merged.getTaskStartToCloseTimeout().getSeconds());
        Assert.assertEquals(a.workflowId(), merged.getWorkflowId());
        Assert.assertEquals(a.workflowIdReusePolicy(), merged.getWorkflowIdReusePolicy());
    }

    @Test
    public void testBothPresent() throws NoSuchMethodException {
        WorkflowOptions o = new WorkflowOptions.Builder()
                .setTaskList("foo")
                .setExecutionStartToCloseTimeout(Duration.ofSeconds(321))
                .setTaskStartToCloseTimeout(Duration.ofSeconds(13))
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.RejectDuplicate)
                .setWorkflowId("bar")
                .build();
        WorkflowMethod a = WorkflowOptionsTest.class
                .getMethod("workflowOptions")
                .getAnnotation(WorkflowMethod.class);
        Assert.assertEquals(o, WorkflowOptions.merge(a, o));
    }
}
