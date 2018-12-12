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

package com.uber.cadence.internal.sync;

import com.uber.cadence.activity.ActivityTask;
import com.uber.cadence.serviceclient.IWorkflowService;
import java.lang.reflect.Type;
import java.util.Optional;

public final class ActivityInternal {

  private ActivityInternal() {}

  static ActivityExecutionContext getContext() {
    return CurrentActivityExecutionContext.get();
  }

  public static <V> void recordActivityHeartbeat(V details) {
    getContext().recordActivityHeartbeat(details);
  }

  public static ActivityTask getTask() {
    return getContext().getTask();
  }

  public static String getDomain() {
    return getContext().getDomain();
  }

  public static IWorkflowService getService() {
    return getContext().getService();
  }

  public static void doNotCompleteOnReturn() {
    getContext().doNotCompleteOnReturn();
  }

  public static <V> Optional<V> getHeartbeatDetails(Class<V> detailsClass, Type detailsType) {
    return getContext().getHeartbeatDetails(detailsClass, detailsType);
  }
}
