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

package io.temporal.internal.sync;

import io.temporal.activity.ActivityInfo;
import io.temporal.api.common.v1.Header;
import io.temporal.api.common.v1.Payloads;
import io.temporal.workflow.Functions;
import java.util.Optional;

/**
 * An extension for {@link ActivityInfo} with information about the activity task that the current
 * activity is handling that should be available for Temporal SDK, but shouldn't be available or
 * exposed to Activity implementation code.
 */
public interface ActivityInfoInternal extends ActivityInfo {
  /**
   * @return function shat should be triggered after activity completion with any outcome (success,
   *     failure, cancelling)
   */
  Functions.Proc getCompletionHandle();

  /** @return input parameters of the activity execution */
  Optional<Payloads> getInput();

  /** @return header that is passed with the activity execution */
  Header getHeader();
}
