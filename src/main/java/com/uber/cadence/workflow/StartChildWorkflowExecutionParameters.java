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
package com.uber.cadence.workflow;

import com.uber.cadence.StartWorkflowOptions;
import com.uber.cadence.ChildPolicy;
import com.uber.cadence.WorkflowType;

public final class StartChildWorkflowExecutionParameters implements Cloneable {

    private String control;

    private int executionStartToCloseTimeoutSeconds;

    private byte[] input;

    private String taskList;

    private int taskStartToCloseTimeoutSeconds;

    private String workflowId;

    private WorkflowType workflowType;

    private ChildPolicy childPolicy;

    public StartChildWorkflowExecutionParameters() {
    }

    public String getControl() {
        return control;
    }

    public void setControl(String control) {
        this.control = control;
    }

    public StartChildWorkflowExecutionParameters withControl(String control) {
        this.control = control;
        return this;
    }

    public int getExecutionStartToCloseTimeoutSeconds() {
        return executionStartToCloseTimeoutSeconds;
    }

    public void setExecutionStartToCloseTimeoutSeconds(int executionStartToCloseTimeoutSeconds) {
        this.executionStartToCloseTimeoutSeconds = executionStartToCloseTimeoutSeconds;
    }

    public StartChildWorkflowExecutionParameters withExecutionStartToCloseTimeoutSeconds(int executionStartToCloseTimeoutSeconds) {
        this.executionStartToCloseTimeoutSeconds = executionStartToCloseTimeoutSeconds;
        return this;
    }

    public byte[] getInput() {
        return input;
    }

    public void setInput(byte[] input) {
        this.input = input;
    }

    public StartChildWorkflowExecutionParameters withInput(byte[] input) {
        this.input = input;
        return this;
    }

    public String getTaskList() {
        return taskList;
    }

    public void setTaskList(String taskList) {
        this.taskList = taskList;
    }

    public StartChildWorkflowExecutionParameters withTaskList(String taskList) {
        this.taskList = taskList;
        return this;
    }

    public int getTaskStartToCloseTimeoutSeconds() {
        return taskStartToCloseTimeoutSeconds;
    }

    public void setTaskStartToCloseTimeoutSeconds(int taskStartToCloseTimeoutSeconds) {
        this.taskStartToCloseTimeoutSeconds = taskStartToCloseTimeoutSeconds;
    }

    public StartChildWorkflowExecutionParameters withTaskStartToCloseTimeoutSeconds(int taskStartToCloseTimeoutSeconds) {
        this.taskStartToCloseTimeoutSeconds = taskStartToCloseTimeoutSeconds;
        return this;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
    }

    public StartChildWorkflowExecutionParameters withWorkflowId(String workflowId) {
        this.workflowId = workflowId;
        return this;
    }

    public WorkflowType getWorkflowType() {
        return workflowType;
    }

    public void setWorkflowType(WorkflowType workflowType) {
        this.workflowType = workflowType;
    }

    public StartChildWorkflowExecutionParameters withWorkflowType(WorkflowType workflowType) {
        this.workflowType = workflowType;
        return this;
    }

    public ChildPolicy getChildPolicy() {
        return childPolicy;
    }

    public void setChildPolicy(ChildPolicy childPolicy) {
        this.childPolicy = childPolicy;
    }

    public StartChildWorkflowExecutionParameters withChildPolicy(ChildPolicy childPolicy) {
        this.childPolicy = childPolicy;
        return this;
    }

    public StartChildWorkflowExecutionParameters createStartChildWorkflowExecutionParametersFromOptions(
            StartWorkflowOptions options, StartWorkflowOptions optionsOverride) {
        StartChildWorkflowExecutionParameters startChildWorkflowExecutionParameters = this.clone();

        if (options != null) {

            Integer executionStartToCloseTimeoutSeconds = options.getExecutionStartToCloseTimeoutSeconds();
            if (executionStartToCloseTimeoutSeconds != null) {
                startChildWorkflowExecutionParameters.setExecutionStartToCloseTimeoutSeconds(executionStartToCloseTimeoutSeconds);
            }

            Integer taskStartToCloseTimeoutSeconds = options.getTaskStartToCloseTimeoutSeconds();
            if (taskStartToCloseTimeoutSeconds != null) {
                startChildWorkflowExecutionParameters.setTaskStartToCloseTimeoutSeconds(taskStartToCloseTimeoutSeconds);
            }

            String taskList = options.getTaskList();
            if (taskList != null && !taskList.isEmpty()) {
                startChildWorkflowExecutionParameters.setTaskList(taskList);
            }

            ChildPolicy childPolicy = options.getChildPolicy();
            if (childPolicy != null) {
                startChildWorkflowExecutionParameters.setChildPolicy(childPolicy);
            }
        }

        if (optionsOverride != null) {
            Integer executionStartToCloseTimeoutSeconds = optionsOverride.getExecutionStartToCloseTimeoutSeconds();
            if (executionStartToCloseTimeoutSeconds != null) {
                startChildWorkflowExecutionParameters.setExecutionStartToCloseTimeoutSeconds(executionStartToCloseTimeoutSeconds);
            }

            Integer taskStartToCloseTimeoutSeconds = optionsOverride.getTaskStartToCloseTimeoutSeconds();
            if (taskStartToCloseTimeoutSeconds != null) {
                startChildWorkflowExecutionParameters.setTaskStartToCloseTimeoutSeconds(taskStartToCloseTimeoutSeconds);
            }

            String taskList = optionsOverride.getTaskList();
            if (taskList != null && !taskList.isEmpty()) {
                startChildWorkflowExecutionParameters.setTaskList(taskList);
            }

            ChildPolicy childPolicy = optionsOverride.getChildPolicy();
            if (childPolicy != null) {
                startChildWorkflowExecutionParameters.setChildPolicy(childPolicy);
            }
        }

        return startChildWorkflowExecutionParameters;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("WorkflowType: " + workflowType + ", ");
        sb.append("WorkflowId: " + workflowId + ", ");
        sb.append("Input: " + input + ", ");
        sb.append("Control: " + control + ", ");
        sb.append("ExecutionStartToCloseTimeout: " + executionStartToCloseTimeoutSeconds + ", ");
        sb.append("TaskStartToCloseTimeout: " + taskStartToCloseTimeoutSeconds + ", ");
        sb.append("ChildPolicy: " + childPolicy + ", ");
        sb.append("TaskList: " + taskList + ", ");
        sb.append("}");
        return sb.toString();
    }

    public StartChildWorkflowExecutionParameters clone() {
        StartChildWorkflowExecutionParameters result = new StartChildWorkflowExecutionParameters();
        result.setControl(control);
        result.setExecutionStartToCloseTimeoutSeconds(executionStartToCloseTimeoutSeconds);
        result.setInput(input);
        result.setChildPolicy(childPolicy);
        result.setTaskList(taskList);
        result.setTaskStartToCloseTimeoutSeconds(taskStartToCloseTimeoutSeconds);
        result.setWorkflowId(workflowId);
        result.setWorkflowType(workflowType);
        return result;
    }

}
