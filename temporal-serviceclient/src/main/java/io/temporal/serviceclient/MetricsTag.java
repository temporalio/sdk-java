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

import com.uber.m3.tally.Scope;
import com.uber.m3.util.ImmutableMap;
import io.grpc.CallOptions;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class MetricsTag {
  public static final String NAMESPACE = "namespace";
  public static final String TASK_QUEUE = "task_queue";
  public static final String WORKER_TYPE = "worker_type";

  public static final String ACTIVITY_TYPE = "activity_type";
  public static final String WORKFLOW_TYPE = "workflow_type";
  public static final String NEXUS_SERVICE = "nexus_service";
  public static final String NEXUS_OPERATION = "nexus_operation";
  public static final String SIGNAL_NAME = "signal_name";
  public static final String QUERY_TYPE = "query_type";
  public static final String UPDATE_NAME = "update_name";
  public static final String STATUS_CODE = "status_code";
  public static final String EXCEPTION = "exception";
  public static final String OPERATION_NAME = "operation";
  public static final String TASK_FAILURE_TYPE = "failure_reason";

  /** Used to pass metrics scope to the interceptor */
  public static final CallOptions.Key<Scope> METRICS_TAGS_CALL_OPTIONS_KEY =
      CallOptions.Key.create("metrics-tags-call-options-key");

  /** Indicates to interceptors that GetWorkflowExecutionHistory is a long poll. */
  public static final CallOptions.Key<Boolean> HISTORY_LONG_POLL_CALL_OPTIONS_KEY =
      CallOptions.Key.create("history-long-poll");

  public static final String DEFAULT_VALUE = "none";

  private static final ConcurrentMap<String, Map<String, String>> tagsByNamespace =
      new ConcurrentHashMap<>();

  public interface TagValue {
    String getTag();

    String getValue();
  }

  /** Returns a set of default metric tags for a given namespace. */
  public static Map<String, String> defaultTags(String namespace) {
    return tagsByNamespace.computeIfAbsent(namespace, MetricsTag::tags);
  }

  private static Map<String, String> tags(String namespace) {
    return new ImmutableMap.Builder<String, String>(9)
        .put(NAMESPACE, namespace)
        .put(ACTIVITY_TYPE, DEFAULT_VALUE)
        .put(OPERATION_NAME, DEFAULT_VALUE)
        .put(SIGNAL_NAME, DEFAULT_VALUE)
        .put(QUERY_TYPE, DEFAULT_VALUE)
        .put(TASK_QUEUE, DEFAULT_VALUE)
        .put(STATUS_CODE, DEFAULT_VALUE)
        .put(EXCEPTION, DEFAULT_VALUE)
        .put(WORKFLOW_TYPE, DEFAULT_VALUE)
        .put(WORKER_TYPE, DEFAULT_VALUE)
        .build();
  }

  public static Scope tagged(Scope scope, String tagName, String tagValue) {
    return scope.tagged(Collections.singletonMap(tagName, tagValue));
  }

  public static Scope tagged(Scope scope, TagValue tagValue) {
    return tagged(scope, tagValue.getTag(), tagValue.getValue());
  }
}
