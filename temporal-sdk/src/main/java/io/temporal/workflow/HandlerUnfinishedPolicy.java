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

package io.temporal.workflow;

/**
 * Actions taken if a workflow terminates with running handlers.
 *
 * <p>Policy defining actions taken when a workflow exits while update or signal handlers are
 * running. The workflow exit may be due to successful return, failure, cancellation, or
 * continue-as-new.
 */
public enum HandlerUnfinishedPolicy {
  /** Issue a warning in addition to abandon. */
  WARN_AND_ABANDON,
  /**
   * Abandon the handler.
   *
   * <p>In the case of an update handler this means that the client will receive an error rather
   * than the update result.
   */
  ABANDON,
}
