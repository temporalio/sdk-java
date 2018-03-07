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

import com.uber.cadence.ChildPolicy;
import com.uber.cadence.WorkflowIdReusePolicy;
import com.uber.cadence.WorkflowType;
import com.uber.cadence.client.WorkflowOptions;

public class StartWorkflowExecutionParameters {

    private String workflowId;

    private WorkflowType workflowType;

    private String taskList;

    private byte[] input;

    private long executionStartToCloseTimeoutSeconds;

    private long taskStartToCloseTimeoutSeconds;

    private ChildPolicy childPolicy;

    private WorkflowIdReusePolicy workflowIdReusePolicy;

    /**
     * Returns the value of the WorkflowId property for this object.
     * <p>
     * <b>Constraints:</b><br/>
     * <b>Length: </b>1 - 64<br/>
     *
     * @return The value of the WorkflowId property for this object.
     */
    public String getWorkflowId() {
        return workflowId;
    }

    /**
     * Sets the value of the WorkflowId property for this object.
     * <p>
     * <b>Constraints:</b><br/>
     * <b>Length: </b>1 - 64<br/>
     *
     * @param workflowId The new value for the WorkflowId property for this object.
     */
    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
    }

    /**
     * Sets the value of the WorkflowId property for this object.
     * <p>
     * Returns a reference to this object so that method calls can be chained together.
     * <p>
     * <b>Constraints:</b><br/>
     * <b>Length: </b>1 - 64<br/>
     *
     * @param workflowId The new value for the WorkflowId property for this object.
     * @return A reference to this updated object so that method calls can be chained
     * together.
     */
    public StartWorkflowExecutionParameters withWorkflowId(String workflowId) {
        this.workflowId = workflowId;
        return this;
    }


    /**
     * Returns the value of the WorkflowType property for this object.
     *
     * @return The value of the WorkflowType property for this object.
     */
    public WorkflowType getWorkflowType() {
        return workflowType;
    }

    /**
     * Sets the value of the WorkflowType property for this object.
     *
     * @param workflowType The new value for the WorkflowType property for this object.
     */
    public void setWorkflowType(WorkflowType workflowType) {
        this.workflowType = workflowType;
    }

    /**
     * Sets the value of the WorkflowType property for this object.
     * <p>
     * Returns a reference to this object so that method calls can be chained together.
     *
     * @param workflowType The new value for the WorkflowType property for this object.
     * @return A reference to this updated object so that method calls can be chained
     * together.
     */
    public StartWorkflowExecutionParameters withWorkflowType(WorkflowType workflowType) {
        this.workflowType = workflowType;
        return this;
    }

    public WorkflowIdReusePolicy getWorkflowIdReusePolicy() {
        return workflowIdReusePolicy;
    }

    public void setWorkflowIdReusePolicy(WorkflowIdReusePolicy workflowIdReusePolicy) {
        this.workflowIdReusePolicy = workflowIdReusePolicy;
    }

    public StartWorkflowExecutionParameters withWorkflowIdReusePolicy(WorkflowIdReusePolicy workflowIdReusePolicy) {
        this.workflowIdReusePolicy = workflowIdReusePolicy;
        return this;
    }

    /**
     * Returns the value of the TaskList property for this object.
     *
     * @return The value of the TaskList property for this object.
     */
    public String getTaskList() {
        return taskList;
    }

    /**
     * Sets the value of the TaskList property for this object.
     *
     * @param taskList The new value for the TaskList property for this object.
     */
    public void setTaskList(String taskList) {
        this.taskList = taskList;
    }

    /**
     * Sets the value of the TaskList property for this object.
     * <p>
     * Returns a reference to this object so that method calls can be chained together.
     *
     * @param taskList The new value for the TaskList property for this object.
     * @return A reference to this updated object so that method calls can be chained
     * together.
     */
    public StartWorkflowExecutionParameters withTaskList(String taskList) {
        this.taskList = taskList;
        return this;
    }


    /**
     * Returns the value of the Input property for this object.
     * <p>
     * <b>Constraints:</b><br/>
     * <b>Length: </b>0 - 100000<br/>
     *
     * @return The value of the Input property for this object.
     */
    public byte[] getInput() {
        return input;
    }

    /**
     * Sets the value of the Input property for this object.
     * <p>
     * <b>Constraints:</b><br/>
     * <b>Length: </b>0 - 100000<br/>
     *
     * @param input The new value for the Input property for this object.
     */
    public void setInput(byte[] input) {
        this.input = input;
    }

    /**
     * Sets the value of the Input property for this object.
     * <p>
     * Returns a reference to this object so that method calls can be chained together.
     * <p>
     * <b>Constraints:</b><br/>
     * <b>Length: </b>0 - 100000<br/>
     *
     * @param input The new value for the Input property for this object.
     * @return A reference to this updated object so that method calls can be chained
     * together.
     */
    public StartWorkflowExecutionParameters withInput(byte[] input) {
        this.input = input;
        return this;
    }


    /**
     * Returns the value of the StartToCloseTimeout property for this object.
     * <p>
     * <b>Constraints:</b><br/>
     * <b>Length: </b>0 - 64<br/>
     *
     * @return The value of the StartToCloseTimeout property for this object.
     */
    public long getExecutionStartToCloseTimeoutSeconds() {
        return executionStartToCloseTimeoutSeconds;
    }

    /**
     * Sets the value of the StartToCloseTimeout property for this object.
     * <p>
     * <b>Constraints:</b><br/>
     * <b>Length: </b>0 - 64<br/>
     *
     * @param executionStartToCloseTimeoutSeconds The new value for the StartToCloseTimeout property for this object.
     */
    public void setExecutionStartToCloseTimeoutSeconds(long executionStartToCloseTimeoutSeconds) {
        this.executionStartToCloseTimeoutSeconds = executionStartToCloseTimeoutSeconds;
    }

    /**
     * Sets the value of the StartToCloseTimeout property for this object.
     * <p>
     * Returns a reference to this object so that method calls can be chained together.
     * <p>
     * <b>Constraints:</b><br/>
     * <b>Length: </b>0 - 64<br/>
     *
     * @param executionStartToCloseTimeoutSeconds The new value for the StartToCloseTimeout property for this object.
     * @return A reference to this updated object so that method calls can be chained
     * together.
     */
    public StartWorkflowExecutionParameters withExecutionStartToCloseTimeoutSeconds(long executionStartToCloseTimeoutSeconds) {
        this.executionStartToCloseTimeoutSeconds = executionStartToCloseTimeoutSeconds;
        return this;
    }

    public long getTaskStartToCloseTimeoutSeconds() {
        return taskStartToCloseTimeoutSeconds;
    }

    public void setTaskStartToCloseTimeoutSeconds(long taskStartToCloseTimeoutSeconds) {
        this.taskStartToCloseTimeoutSeconds = taskStartToCloseTimeoutSeconds;
    }

    public StartWorkflowExecutionParameters withTaskStartToCloseTimeoutSeconds(int taskStartToCloseTimeoutSeconds) {
        this.taskStartToCloseTimeoutSeconds = taskStartToCloseTimeoutSeconds;
        return this;
    }

    public ChildPolicy getChildPolicy() {
        return childPolicy;
    }

    public void setChildPolicy(ChildPolicy childPolicy) {
        this.childPolicy = childPolicy;
    }

    public StartWorkflowExecutionParameters withChildPolicy(ChildPolicy childPolicy) {
        this.childPolicy = childPolicy;
        return this;
    }

    public static StartWorkflowExecutionParameters createStartWorkflowExecutionParametersFromOptions(WorkflowOptions options) {
        StartWorkflowExecutionParameters parameters = new StartWorkflowExecutionParameters();
        parameters.setExecutionStartToCloseTimeoutSeconds((int) options.getExecutionStartToCloseTimeout().getSeconds());
        parameters.setTaskStartToCloseTimeoutSeconds((int) options.getTaskStartToCloseTimeout().getSeconds());
        parameters.setTaskList(options.getTaskList());
        parameters.setChildPolicy(options.getChildPolicy());
        return parameters;
    }

    /**
     * Returns a string representation of this object; useful for testing and
     * debugging.
     *
     * @return A string representation of this object.
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("WorkflowId: " + workflowId + ", ");
        sb.append("WorkflowType: " + workflowType + ", ");
        sb.append("TaskList: " + taskList + ", ");
        sb.append("Input: " + input + ", ");
        sb.append("StartToCloseTimeout: " + executionStartToCloseTimeoutSeconds + ", ");
        sb.append("ChildPolicy: " + childPolicy + ", ");
        sb.append("}");
        return sb.toString();
    }

    public StartWorkflowExecutionParameters clone() {
        StartWorkflowExecutionParameters result = new StartWorkflowExecutionParameters();
        result.setInput(input);
        result.setExecutionStartToCloseTimeoutSeconds(executionStartToCloseTimeoutSeconds);
        result.setTaskStartToCloseTimeoutSeconds(taskStartToCloseTimeoutSeconds);
        result.setTaskList(taskList);
        result.setWorkflowId(workflowId);
        result.setWorkflowType(workflowType);
        result.setChildPolicy(childPolicy);
        return result;
    }
}
    
