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

package com.uber.cadence.internal.logging;

public class LoggerTag {
  public static final String ACTIVITY_ID = "ActivityID";
  public static final String ACTIVITY_TYPE = "ActivityType";
  public static final String DOMAIN = "Domain";
  public static final String EVENT_ID = "EventID";
  public static final String EVENT_TYPE = "EventType";
  public static final String RUN_ID = "RunID";
  public static final String TASK_LIST = "TaskList";
  public static final String TIMER_ID = "TimerID";
  public static final String WORKFLOW_ID = "WorkflowID";
  public static final String WORKFLOW_TYPE = "WorkflowType";
  public static final String WORKER_ID = "WorkerID";
  public static final String WORKER_TYPE = "WorkerType";
  public static final String SIDE_EFFECT_ID = "SideEffectID";
  public static final String CHILD_WORKFLOW_ID = "ChildWorkflowID";
}
