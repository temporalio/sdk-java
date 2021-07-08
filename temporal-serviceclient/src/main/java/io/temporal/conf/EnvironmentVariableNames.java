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
