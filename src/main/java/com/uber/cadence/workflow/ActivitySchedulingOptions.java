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


public final class ActivitySchedulingOptions {
    
    private Integer heartbeatTimeoutSeconds;
    
    private Integer scheduleToCloseTimeoutSeconds;
    
    private Integer scheduleToStartTimeoutSeconds;
	
    private Integer startToCloseTimeoutSeconds;
	
    private String taskList;
	
	public Integer getHeartbeatTimeoutSeconds() {
        return heartbeatTimeoutSeconds;
    }

    public void setHeartbeatTimeoutSeconds(Integer heartbeatTimeoutSeconds) {
        this.heartbeatTimeoutSeconds = heartbeatTimeoutSeconds;
    }
    
    public ActivitySchedulingOptions withHeartbeatTimeoutSeconds(Integer heartbeatTimeoutSeconds) {
        this.heartbeatTimeoutSeconds = heartbeatTimeoutSeconds;
        return this;
    }
	
	public Integer getScheduleToCloseTimeoutSeconds() {
		return scheduleToCloseTimeoutSeconds;
	}
	
	public void setScheduleToCloseTimeoutSeconds(Integer scheduleToCloseTimeoutSeconds) {
		this.scheduleToCloseTimeoutSeconds = scheduleToCloseTimeoutSeconds;
	}
	
	public ActivitySchedulingOptions withScheduleToCloseTimeoutSeconds(Integer scheduleToCloseTimeoutSeconds) {
		this.scheduleToCloseTimeoutSeconds = scheduleToCloseTimeoutSeconds;
		return this;
	}
	
	public Integer getScheduleToStartTimeoutSeconds() {
		return scheduleToStartTimeoutSeconds;
	}
	
	public void setScheduleToStartTimeoutSeconds(Integer scheduleToStartTimeoutSeconds) {
		this.scheduleToStartTimeoutSeconds = scheduleToStartTimeoutSeconds;
	}
	
	public ActivitySchedulingOptions withScheduleToStartTimeoutSeconds(Integer scheduleToStartTimeoutSeconds) {
		this.scheduleToStartTimeoutSeconds = scheduleToStartTimeoutSeconds;
		return this;
	}
	
	public Integer getStartToCloseTimeoutSeconds() {
        return startToCloseTimeoutSeconds;
    }

    public void setStartToCloseTimeoutSeconds(Integer startToCloseTimeoutSeconds) {
        this.startToCloseTimeoutSeconds = startToCloseTimeoutSeconds;
    }
    
    public ActivitySchedulingOptions withStartToCloseTimeoutSeconds(Integer startToCloseTimeoutSeconds) {
        this.startToCloseTimeoutSeconds = startToCloseTimeoutSeconds;
        return this;
    }
	
	public String getTaskList() {
		return taskList;
	}
	
	public void setTaskList(String taskList) {
		this.taskList = taskList;
	}
	
	public ActivitySchedulingOptions withTaskList(String taskList) {
		this.taskList = taskList;
		return this;
	}
}
