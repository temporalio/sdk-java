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

package io.temporal.opentracing;

public enum SpanOperationType {
  START_WORKFLOW("StartWorkflow"),
  SIGNAL_WITH_START_WORKFLOW("SignalWithStartWorkflow"),
  RUN_WORKFLOW("RunWorkflow"),
  START_CHILD_WORKFLOW("StartChildWorkflow"),
  START_ACTIVITY("StartActivity"),
  RUN_ACTIVITY("RunActivity");

  private final String defaultPrefix;

  SpanOperationType(String defaultPrefix) {
    this.defaultPrefix = defaultPrefix;
  }

  public String getDefaultPrefix() {
    return defaultPrefix;
  }
}
