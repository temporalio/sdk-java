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

package io.temporal.worker.tuning;

import io.temporal.common.Experimental;

/** Implementors determine how resource usage is measured. */
@Experimental
public interface SystemResourceInfo {
  /**
   * @return System-wide CPU usage as a percentage [0.0, 1.0]
   */
  double getCPUUsagePercent();

  /**
   * @return Memory usage as a percentage [0.0, 1.0]. Memory usage should reflect either system-wide
   *     usage or JVM-specific usage, whichever is higher, to avoid running out of memory in either
   *     way.
   */
  double getMemoryUsagePercent();
}
