/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
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

package io.temporal.internal.logging;

public class LoggerTag {
  public static final String ACTIVITY_ID = "ActivityId";
  public static final String ACTIVITY_TYPE = "ActivityType";
  public static final String NAMESPACE = "Namespace";
  public static final String EVENT_ID = "EventId";
  public static final String EVENT_TYPE = "EventType";
  public static final String RUN_ID = "RunId";
  public static final String TASK_QUEUE = "TaskQueue";
  public static final String TIMER_ID = "TimerId";
  public static final String WORKFLOW_ID = "WorkflowId";
  public static final String WORKFLOW_TYPE = "WorkflowType";
  public static final String WORKER_ID = "WorkerId";
  public static final String WORKER_TYPE = "WorkerType";
  public static final String SIDE_EFFECT_ID = "SideEffectId";
  public static final String CHILD_WORKFLOW_ID = "ChildWorkflowId";
}
