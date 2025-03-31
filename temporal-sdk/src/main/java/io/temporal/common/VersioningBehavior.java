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

package io.temporal.common;

/** Specifies when a workflow might move from a worker of one Build Id to another. */
@Experimental
public enum VersioningBehavior {
  /**
   * An unspecified versioning behavior. By default, workers opting into worker versioning will be
   * required to specify a behavior. TODO: Link to documentation.
   */
  VERSIONING_BEHAVIOR_UNSPECIFIED,
  /** The workflow will be pinned to the current Build ID unless manually moved. */
  VERSIONING_BEHAVIOR_PINNED,
  /**
   * The workflow will automatically move to the latest version (default Build ID of the task queue)
   * when the next task is dispatched.
   */
  VERSIONING_BEHAVIOR_AUTO_UPGRADE
}
