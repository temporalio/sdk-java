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
package com.uber.cadence.internal.generic;

import com.uber.cadence.ActivityType;

public class ExecuteActivityParameters implements Cloneable {
    private String activityId;
    private ActivityType activityType;
//    private String control;
    private long heartbeatTimeoutSeconds;
    private byte[] input;
    private long scheduleToCloseTimeoutSeconds;
    private long scheduleToStartTimeoutSeconds;
    private long startToCloseTimeoutSeconds;
    private String taskList;
//    private int taskPriority;
    
    public ExecuteActivityParameters() {
    }
    
//    /**
//     * Returns the value of the Control property for this object.
//     * <p>
//     * <b>Constraints:</b><br/>
//     * <b>Length: </b>0 - 100000<br/>
//     *
//     * @return The value of the Control property for this object.
//     */
//    public String getControl() {
//        return control;
//    }
//
//    /**
//     * Sets the value of the Control property for this object.
//     * <p>
//     * <b>Constraints:</b><br/>
//     * <b>Length: </b>0 - 100000<br/>
//     *
//     * @param control The new value for the Control property for this object.
//     */
//    public void setControl(String control) {
//        this.control = control;
//    }
//
//    /**
//     * Sets the value of the Control property for this object.
//     * <p>
//     * Returns a reference to this object so that method calls can be chained together.
//     * <p>
//     * <b>Constraints:</b><br/>
//     * <b>Length: </b>0 - 100000<br/>
//     *
//     * @param control The new value for the Control property for this object.
//     *
//     * @return A reference to this updated object so that method calls can be chained
//     *         together.
//     */
//    public ExecuteActivityParameters withControl(String control) {
//        this.control = control;
//        return this;
//    }
    
    /**
     * Returns the value of the ActivityType property for this object.
     *
     * @return The value of the ActivityType property for this object.
     */
    public ActivityType getActivityType() {
        return activityType;
    }
    
    /**
     * Sets the value of the ActivityType property for this object.
     *
     * @param activityType The new value for the ActivityType property for this object.
     */
    public void setActivityType(ActivityType activityType) {
        this.activityType = activityType;
    }
    
    /**
     * Sets the value of the ActivityType property for this object.
     * <p>
     * Returns a reference to this object so that method calls can be chained together.
     *
     * @param activityType The new value for the ActivityType property for this object.
     *
     * @return A reference to this updated object so that method calls can be chained 
     *         together. 
     */
    public ExecuteActivityParameters withActivityType(ActivityType activityType) {
        this.activityType = activityType;
        return this;
    }
    
    /**
     * Returns the value of the ActivityId property for this object.
     * <p>
     * <b>Constraints:</b><br/>
     * <b>Length: </b>1 - 64<br/>
     *
     * @return The value of the ActivityId property for this object.
     */
    public String getActivityId() {
        return activityId;
    }
    
    /**
     * Sets the value of the ActivityId property for this object.
     * <p>
     * <b>Constraints:</b><br/>
     * <b>Length: </b>1 - 64<br/>
     *
     * @param activityId The new value for the ActivityId property for this object.
     */
    public void setActivityId(String activityId) {
        this.activityId = activityId;
    }
    
    /**
     * Sets the value of the ActivityId property for this object.
     * <p>
     * Returns a reference to this object so that method calls can be chained together.
     * <p>
     * <b>Constraints:</b><br/>
     * <b>Length: </b>1 - 64<br/>
     *
     * @param activityId The new value for the ActivityId property for this object.
     *
     * @return A reference to this updated object so that method calls can be chained 
     *         together. 
     */
    public ExecuteActivityParameters withActivityId(String activityId) {
        this.activityId = activityId;
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
     *
     * @return A reference to this updated object so that method calls can be chained 
     *         together. 
     */
    public ExecuteActivityParameters withInput(byte[] input) {
        this.input = input;
        return this;
    }    
    
    public long getHeartbeatTimeoutSeconds() {
        return heartbeatTimeoutSeconds;
    }

    
    public void setHeartbeatTimeoutSeconds(long heartbeatTimeoutSeconds) {
        this.heartbeatTimeoutSeconds = heartbeatTimeoutSeconds;
    }
    
    public ExecuteActivityParameters withHeartbeatTimeoutSeconds(long heartbeatTimeoutSeconds) {
        this.heartbeatTimeoutSeconds = heartbeatTimeoutSeconds;
        return this;
    }
    
    
    /**
     * Returns the value of the ScheduleToStartTimeout property for this
     * object.
     * <p>
     * <b>Constraints:</b><br/>
     * <b>Length: </b>1 - 64<br/>
     *
     * @return The value of the ScheduleToStartTimeout property for this object.
     */
    public long getScheduleToStartTimeoutSeconds() {
        return scheduleToStartTimeoutSeconds;
    }
    
    /**
     * Sets the value of the ScheduleToStartTimeout property for this object.
     * <p>
     * <b>Constraints:</b><br/>
     * <b>Length: </b>1 - 64<br/>
     *
     * @param scheduleToStartTimeoutSeconds The new value for the ScheduleToStartTimeout property for this object.
     */
    public void setScheduleToStartTimeoutSeconds(long scheduleToStartTimeoutSeconds) {
        this.scheduleToStartTimeoutSeconds = scheduleToStartTimeoutSeconds;
    }
    
    /**
     * Sets the value of the ScheduleToStartTimeout property for this object.
     * <p>
     * Returns a reference to this object so that method calls can be chained together.
     * <p>
     * <b>Constraints:</b><br/>
     * <b>Length: </b>1 - 64<br/>
     *
     * @param scheduleToStartTimeoutSeconds The new value for the ScheduleToStartTimeout property for this object.
     *
     * @return A reference to this updated object so that method calls can be chained 
     *         together. 
     */
    public ExecuteActivityParameters withScheduleToStartTimeoutSeconds(long scheduleToStartTimeoutSeconds) {
        this.scheduleToStartTimeoutSeconds = scheduleToStartTimeoutSeconds;
        return this;
    }
    
    
    /**
     * Returns the value of the ScheduleToCloseTimeout property for this
     * object.
     * <p>
     * <b>Constraints:</b><br/>
     * <b>Length: </b>1 - 64<br/>
     *
     * @return The value of the ScheduleToCloseTimeout property for this object.
     */
    public long getScheduleToCloseTimeoutSeconds() {
        return scheduleToCloseTimeoutSeconds;
    }
    
    /**
     * Sets the value of the ScheduleToCloseTimeout property for this object.
     * <p>
     * <b>Constraints:</b><br/>
     * <b>Length: </b>1 - 64<br/>
     *
     * @param scheduleToCloseTimeoutSeconds The new value for the ScheduleToCloseTimeout property for this object.
     */
    public void setScheduleToCloseTimeoutSeconds(long scheduleToCloseTimeoutSeconds) {
        this.scheduleToCloseTimeoutSeconds = scheduleToCloseTimeoutSeconds;
    }
    
    /**
     * Sets the value of the ScheduleToCloseTimeout property for this object.
     * <p>
     * Returns a reference to this object so that method calls can be chained together.
     * <p>
     * <b>Constraints:</b><br/>
     * <b>Length: </b>1 - 64<br/>
     *
     * @param scheduleToCloseTimeoutSeconds The new value for the ScheduleToCloseTimeout property for this object.
     *
     * @return A reference to this updated object so that method calls can be chained 
     *         together. 
     */
    public ExecuteActivityParameters withScheduleToCloseTimeoutSeconds(long scheduleToCloseTimeoutSeconds) {
        this.scheduleToCloseTimeoutSeconds = scheduleToCloseTimeoutSeconds;
        return this;
    }
    
    public long getStartToCloseTimeoutSeconds() {
        return startToCloseTimeoutSeconds;
    }

    
    public void setStartToCloseTimeoutSeconds(long startToCloseTimeoutSeconds) {
        this.startToCloseTimeoutSeconds = startToCloseTimeoutSeconds;
    }
    
    public ExecuteActivityParameters withStartToCloseTimeoutSeconds(long startToCloseTimeoutSeconds) {
        this.startToCloseTimeoutSeconds = startToCloseTimeoutSeconds;
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
     *
     * @return A reference to this updated object so that method calls can be chained 
     *         together. 
     */
    public ExecuteActivityParameters withTaskList(String taskList) {
        this.taskList = taskList;
        return this;
    }

    /**
     * Returns a string representation of this object; useful for testing and
     * debugging.
     *
     * @return A string representation of this object.
     *
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("ActivityType: " + activityType + ", ");
        sb.append("ActivityId: " + activityId + ", ");
        sb.append("Input: " + input + ", ");
//        sb.append("Control: " + control + ", ");
        sb.append("HeartbeatTimeout: " + heartbeatTimeoutSeconds + ", ");
        sb.append("ScheduleToStartTimeout: " + scheduleToStartTimeoutSeconds + ", ");
        sb.append("ScheduleToCloseTimeout: " + scheduleToCloseTimeoutSeconds + ", ");
        sb.append("StartToCloseTimeout: " + startToCloseTimeoutSeconds + ", ");
        sb.append("TaskList: " + taskList + ", ");
//        sb.append("TaskPriority: " + taskPriority);
        sb.append("}");
        return sb.toString();
    }

    public ExecuteActivityParameters clone() {
        ExecuteActivityParameters result = new ExecuteActivityParameters();
        result.setActivityType(activityType);
        result.setActivityId(activityId);
        result.setInput(input);
//        result.setControl(control);
        result.setHeartbeatTimeoutSeconds(heartbeatTimeoutSeconds);
        result.setScheduleToStartTimeoutSeconds(scheduleToStartTimeoutSeconds);
        result.setScheduleToCloseTimeoutSeconds(scheduleToCloseTimeoutSeconds);
        result.setStartToCloseTimeoutSeconds(startToCloseTimeoutSeconds);
        result.setTaskList(taskList);
//        result.setTaskPriority(taskPriority);
        return result;
    }
}
