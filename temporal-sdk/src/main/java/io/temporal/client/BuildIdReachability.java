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

import io.temporal.api.enums.v1.TaskReachability;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Contains information about the reachability of a specific Build ID. */
public class BuildIdReachability {
  private final Map<String, List<TaskReachability>> taskQueueReachability;
  private final List<String> unretrievedTaskQueues;

  public BuildIdReachability(
      Map<String, List<TaskReachability>> taskQueueReachability,
      List<String> unretrievedTaskQueues) {
    this.taskQueueReachability = taskQueueReachability;
    this.unretrievedTaskQueues = unretrievedTaskQueues;
  }

  /**
   * @return A map of Task Queue names to the reachability status of the Build ID on that queue. If
   *     the value is an empty list, the Build ID is not reachable on that queue.
   */
  public Map<String, List<TaskReachability>> getTaskQueueReachability() {
    return Collections.unmodifiableMap(taskQueueReachability);
  }

  /**
   * @return a list of any Task Queues that could not be retrieved because the server limits the
   *     number that can be queried at once.
   */
  public List<String> getUnretrievedTaskQueues() {
    return Collections.unmodifiableList(unretrievedTaskQueues);
  }
}
