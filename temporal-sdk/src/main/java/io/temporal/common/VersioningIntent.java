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

/**
 * Indicates whether the user intends certain commands to be run on a compatible worker build ID
 * version or not.
 */
public enum VersioningIntent {
  /**
   * Indicates that the SDK should choose the most sensible default behavior for the type of
   * command, accounting for whether the command will be run on the same task queue as the current
   * worker.
   */
  VERSIONING_INTENT_UNSPECIFIED,
  /**
   * Indicates that the command should run on a worker with compatible version if possible. It may
   * not be possible if the target task queue does not also have knowledge of the current worker's
   * build ID.
   */
  VERSIONING_INTENT_COMPATIBLE,
  /**
   * Indicates that the command should run on the target task queue's current overall-default build
   * ID.
   */
  VERSIONING_INTENT_DEFAULT;

  public boolean determineUseCompatibleFlag(boolean destinationTaskQueueIsSame) {
    switch (this) {
      case VERSIONING_INTENT_COMPATIBLE:
        return true;
      case VERSIONING_INTENT_DEFAULT:
        return false;
      default:
        // In the unspecified case, we want to stick to the compatible version as long as the
        // destination queue is the same.
        return destinationTaskQueueIsSame;
    }
  }
}
