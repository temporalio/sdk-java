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

package io.temporal.conf;

public final class EnvironmentVariableNames {
  /**
   * Specify this env variable to disable checks and enforcement for classes that are not intended
   * to be accessed from workflow code.
   *
   * <p>Not specifying it or setting it to "false" (case insensitive) leaves the checks enforced.
   *
   * <p>This option is exposed for backwards compatibility only and should never be enabled for any
   * new code or application.
   */
  public static final String DISABLE_NON_WORKFLOW_CODE_ENFORCEMENTS =
      "TEMPORAL_DISABLE_NON_WORKFLOW_CODE_ENFORCEMENTS";

  private EnvironmentVariableNames() {}
}
