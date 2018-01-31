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
package com.uber.cadence.internal;

import com.uber.cadence.ChildPolicy;

import java.util.List;

public class StartWorkflowOptions {
	
    private Integer executionStartToCloseTimeoutSeconds;
	
    private Integer taskStartToCloseTimeoutSeconds;
    
    private List<String> tagList;
	
    private String taskList;
	
    private ChildPolicy childPolicy;

    public com.uber.cadence.ChildPolicy getChildPolicy() {
        return childPolicy;
    }

    public void setChildPolicy(ChildPolicy childPolicy) {
        this.childPolicy = childPolicy;
    }
    
    public Integer getExecutionStartToCloseTimeoutSeconds() {
		return executionStartToCloseTimeoutSeconds;
	}
	
	public void setExecutionStartToCloseTimeoutSeconds(Integer executionStartToCloseTimeoutSeconds) {
		this.executionStartToCloseTimeoutSeconds = executionStartToCloseTimeoutSeconds;
	}
	
	public StartWorkflowOptions withExecutionStartToCloseTimeoutSeconds(Integer executionStartToCloseTimeoutSeconds) {
		this.executionStartToCloseTimeoutSeconds = executionStartToCloseTimeoutSeconds;
		return this;
	}
	
	public Integer getTaskStartToCloseTimeoutSeconds() {
        return taskStartToCloseTimeoutSeconds;
    }

    public void setTaskStartToCloseTimeoutSeconds(Integer taskStartToCloseTimeoutSeconds) {
        this.taskStartToCloseTimeoutSeconds = taskStartToCloseTimeoutSeconds;
    }
    
    public StartWorkflowOptions withTaskStartToCloseTimeoutSeconds(Integer taskStartToCloseTimeoutSeconds) {
        this.taskStartToCloseTimeoutSeconds = taskStartToCloseTimeoutSeconds;
        return this;
    }
	
	public List<String> getTagList() {
		return tagList;
	}

	public void setTagList(List<String> tagList) {
		this.tagList = tagList;
	}
	
	public StartWorkflowOptions withTagList(List<String> tagList) {
		this.tagList = tagList;
		return this;
	}

	public String getTaskList() {
		return taskList;
	}

	public void setTaskList(String taskList) {
		this.taskList = taskList;
	}
	
	public StartWorkflowOptions withTaskList(String taskList) {
		this.taskList = taskList;
		return this;
	}
	
//    public Integer getTaskPriority() {
//        return taskPriority;
//    }
//
//    public void setTaskPriority(Integer taskPriority) {
//        this.taskPriority = taskPriority;
//    }
//
//    public StartWorkflowOptions withTaskPriority(Integer taskPriority) {
//    	this.taskPriority = taskPriority;
//    	return this;
//    }

}
