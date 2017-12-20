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
package com.uber.cadence.generic;

import com.uber.cadence.StartWorkflowOptions;

import java.util.List;

public class ContinueAsNewWorkflowExecutionParameters {
    private String workflowTypeVersion;
    private int executionStartToCloseTimeoutSeconds;
    private byte[] input;
    private List<String> tagList;
    private String taskList;
    private int taskStartToCloseTimeoutSeconds;

    public ContinueAsNewWorkflowExecutionParameters() {
    }
    
    public String getWorkflowTypeVersion() {
        return workflowTypeVersion;
    }
    
    public void setWorkflowTypeVersion(String workflowTypeVersion) {
        this.workflowTypeVersion = workflowTypeVersion;
    }
    
    public int getExecutionStartToCloseTimeoutSeconds() {
        return executionStartToCloseTimeoutSeconds;
    }
    
    public void setExecutionStartToCloseTimeoutSeconds(int executionStartToCloseTimeoutSeconds) {
        this.executionStartToCloseTimeoutSeconds = executionStartToCloseTimeoutSeconds;
    }
    
    public ContinueAsNewWorkflowExecutionParameters withExecutionStartToCloseTimeoutSeconds(int executionStartToCloseTimeoutSeconds) {
        this.executionStartToCloseTimeoutSeconds = executionStartToCloseTimeoutSeconds;
        return this;
    }

    public byte[] getInput() {
        return input;
    }

    public void setInput(byte[] input) {
        this.input = input;
    }
    
    public ContinueAsNewWorkflowExecutionParameters withInput(byte[] input) {
        this.input = input;
        return this;
    } 

    public List<String> getTagList() {
        return tagList;
    }
    
    public void setTagList(List<String> tagList) {
        this.tagList = tagList;
    }
    
    public ContinueAsNewWorkflowExecutionParameters withTagList(List<String> tagList) {
        this.tagList = tagList;
        return this;
    }

    public String getTaskList() {
        return taskList;
    }
    
    public void setTaskList(String taskList) {
        this.taskList = taskList;
    }
    
    public ContinueAsNewWorkflowExecutionParameters withTaskList(String taskList) {
        this.taskList = taskList;
        return this;
    }

    public int getTaskStartToCloseTimeoutSeconds() {
        return taskStartToCloseTimeoutSeconds;
    }
    
    public void setTaskStartToCloseTimeoutSeconds(int taskStartToCloseTimeoutSeconds) {
        this.taskStartToCloseTimeoutSeconds = taskStartToCloseTimeoutSeconds;
    }
    
    public ContinueAsNewWorkflowExecutionParameters withTaskStartToCloseTimeoutSeconds(int taskStartToCloseTimeoutSeconds) {
        this.taskStartToCloseTimeoutSeconds = taskStartToCloseTimeoutSeconds;
        return this;
    }

    public ContinueAsNewWorkflowExecutionParameters createContinueAsNewParametersFromOptions(StartWorkflowOptions options,
            StartWorkflowOptions optionsOverride) {
        ContinueAsNewWorkflowExecutionParameters continueAsNewWorkflowExecutionParameters = this.clone();
        
        if (options != null) {
            Integer executionStartToCloseTimeoutSeconds = options.getExecutionStartToCloseTimeoutSeconds();
            if (executionStartToCloseTimeoutSeconds != null) {
                continueAsNewWorkflowExecutionParameters.setExecutionStartToCloseTimeoutSeconds(executionStartToCloseTimeoutSeconds);
            }
            
            Integer taskStartToCloseTimeoutSeconds = options.getTaskStartToCloseTimeoutSeconds();
            if (taskStartToCloseTimeoutSeconds != null) {
                continueAsNewWorkflowExecutionParameters.setTaskStartToCloseTimeoutSeconds(taskStartToCloseTimeoutSeconds);
            }
            
            List<String> tagList = options.getTagList();
            if (tagList != null) {
                continueAsNewWorkflowExecutionParameters.setTagList(tagList);
            }
            
            String taskList = options.getTaskList();
            if (taskList != null && !taskList.isEmpty()) { 
                continueAsNewWorkflowExecutionParameters.setTaskList(taskList);
            }
        }
        
        if (optionsOverride != null) {    
            Integer executionStartToCloseTimeoutSeconds = optionsOverride.getExecutionStartToCloseTimeoutSeconds();
            if (executionStartToCloseTimeoutSeconds != null) {
                continueAsNewWorkflowExecutionParameters.setExecutionStartToCloseTimeoutSeconds(executionStartToCloseTimeoutSeconds);
            }
            
            Integer taskStartToCloseTimeoutSeconds = optionsOverride.getTaskStartToCloseTimeoutSeconds();
            if (taskStartToCloseTimeoutSeconds != null) {
                continueAsNewWorkflowExecutionParameters.setTaskStartToCloseTimeoutSeconds(taskStartToCloseTimeoutSeconds);
            }
            
            List<String> tagList = optionsOverride.getTagList();
            if (tagList != null) {
                continueAsNewWorkflowExecutionParameters.setTagList(tagList);
            }
            
            String taskList = optionsOverride.getTaskList();
            if (taskList != null && !taskList.isEmpty()) { 
                continueAsNewWorkflowExecutionParameters.setTaskList(taskList);
            }
        }
        
        return continueAsNewWorkflowExecutionParameters;
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("Input: " + input + ", ");
        sb.append("ExecutionStartToCloseTimeout: " + executionStartToCloseTimeoutSeconds + ", ");
        sb.append("TaskStartToCloseTimeout: " + taskStartToCloseTimeoutSeconds + ", ");
        sb.append("TagList: " + tagList + ", ");
        sb.append("TaskList: " + taskList + ", ");
        sb.append("}");
        return sb.toString();
    }
    
    public ContinueAsNewWorkflowExecutionParameters clone() {
        ContinueAsNewWorkflowExecutionParameters result = new ContinueAsNewWorkflowExecutionParameters();
        result.setWorkflowTypeVersion(workflowTypeVersion);
        result.setExecutionStartToCloseTimeoutSeconds(executionStartToCloseTimeoutSeconds);
        result.setInput(input);
        result.setTagList(tagList);
        result.setTaskList(taskList);
        result.setTaskStartToCloseTimeoutSeconds(taskStartToCloseTimeoutSeconds);
        return result;
    }

}
