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

package com.uber.cadence.client;

import com.uber.cadence.activity.ActivityTask;

/**
 * Usually indicates that activity was already completed (duplicated request to complete) or timed
 * out or workflow is closed.
 */
public final class ActivityCancelledException extends ActivityCompletionException {

  public ActivityCancelledException(ActivityTask task) {
    super(task);
  }

  public ActivityCancelledException() {
    super();
  }
}
