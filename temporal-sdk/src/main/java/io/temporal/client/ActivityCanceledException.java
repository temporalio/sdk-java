/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.client;

import io.temporal.activity.ActivityInfo;

/**
 * Usually indicates that activity was already completed (duplicated request to complete) or timed
 * out or cancellation was requested.
 *
 * <p>Catching this exception directly is discouraged and catching the parent class {@link
 * ActivityCompletionException} is recommended instead.<br>
 * If a workflow gets a cancellation request and it has activities started with {@link
 * io.temporal.activity.ActivityCancellationType#TRY_CANCEL TRY_CANCEL}(default) or {@link
 * io.temporal.activity.ActivityCancellationType#ABANDON ABANDON} cancellation type, the workflow
 * may finish without waiting for activity cancellations and the activities will get {@link
 * ActivityNotExistsException} from their heartbeat, not {@link ActivityCanceledException}. To
 * handle the various edge cases, it's recommended to catch {@link ActivityCompletionException} and
 * treat all the subclasses in the same way.
 */
public final class ActivityCanceledException extends ActivityCompletionException {

  public ActivityCanceledException(ActivityInfo info) {
    super(info);
  }

  public ActivityCanceledException() {
    super();
  }
}
