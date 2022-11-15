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

package io.temporal.testing.internal;

import io.temporal.internal.common.env.EnvironmentVariableUtils;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowRule;

public class ExternalServiceTestConfigurator {
  private static boolean USE_DOCKER_SERVICE =
      EnvironmentVariableUtils.readBooleanFlag("USE_DOCKER_SERVICE");
  private static String TEMPORAL_SERVICE_ADDRESS =
      EnvironmentVariableUtils.readString("TEMPORAL_SERVICE_ADDRESS");

  public static boolean isUseExternalService() {
    return USE_DOCKER_SERVICE;
  }

  public static String getTemporalServiceAddress() {
    return USE_DOCKER_SERVICE
        ? (TEMPORAL_SERVICE_ADDRESS != null ? TEMPORAL_SERVICE_ADDRESS : "127.0.0.1:7233")
        : null;
  }

  public static TestWorkflowRule.Builder configure(TestWorkflowRule.Builder testWorkflowRule) {
    if (USE_DOCKER_SERVICE) {
      testWorkflowRule.setUseExternalService(true);
      if (TEMPORAL_SERVICE_ADDRESS != null) {
        testWorkflowRule.setTarget(TEMPORAL_SERVICE_ADDRESS);
      }
    }
    return testWorkflowRule;
  }

  public static TestEnvironmentOptions.Builder configure(
      TestEnvironmentOptions.Builder testEnvironmentOptions) {
    if (USE_DOCKER_SERVICE) {
      testEnvironmentOptions.setUseExternalService(true);
      if (TEMPORAL_SERVICE_ADDRESS != null) {
        testEnvironmentOptions.setTarget(TEMPORAL_SERVICE_ADDRESS);
      }
    }
    return testEnvironmentOptions;
  }

  public static TestEnvironmentOptions.Builder configuredTestEnvironmentOptions() {
    return configure(TestEnvironmentOptions.newBuilder());
  }
}
