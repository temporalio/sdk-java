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

package io.temporal.serviceclient;

public final class MetricsType {
  private MetricsType() {}

  public static final String TEMPORAL_METRICS_PREFIX = "temporal_";

  public static final String TEMPORAL_REQUEST = TEMPORAL_METRICS_PREFIX + "request";
  public static final String TEMPORAL_REQUEST_FAILURE = TEMPORAL_REQUEST + "_failure";
  public static final String TEMPORAL_REQUEST_LATENCY = TEMPORAL_REQUEST + "_latency";
  public static final String TEMPORAL_LONG_REQUEST = TEMPORAL_METRICS_PREFIX + "long_request";
  public static final String TEMPORAL_LONG_REQUEST_FAILURE = TEMPORAL_LONG_REQUEST + "_failure";
  public static final String TEMPORAL_LONG_REQUEST_LATENCY = TEMPORAL_LONG_REQUEST + "_latency";
}
