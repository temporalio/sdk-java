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

package io.temporal.internal.common;

import io.temporal.activity.ActivityOptions;
import java.util.HashMap;
import java.util.Map;

/**
 * The chain of ActivityOptions and per type options maps. Used to merge options specified at the
 * following layers:
 *
 * <pre>
 *     * WorkflowImplementationOptions
 *     * Workflow
 *     * ActivityStub
 * </pre>
 *
 * Each next layer overrides specific options specified at the previous layer.
 */
public final class MergedActivityOptions {

  /** Common options across all activity types. */
  private ActivityOptions defaultOptions;

  /** Per activity type options. These override defaultOptions. */
  private final Map<String, ActivityOptions> optionsMap = new HashMap<>();

  /** The options specified at the previous layer. They are overriden by this object. */
  private final MergedActivityOptions overridden;

  public MergedActivityOptions(
      MergedActivityOptions overridden,
      ActivityOptions defaultOptions,
      Map<String, ActivityOptions> optionsMap) {
    this.overridden = overridden;
    this.defaultOptions = defaultOptions;
    if (optionsMap != null) {
      this.optionsMap.putAll(optionsMap);
    }
  }

  public MergedActivityOptions(MergedActivityOptions overridden) {
    this.overridden = overridden;
    defaultOptions = null;
  }

  public void setDefaultOptions(ActivityOptions defaultOptions) {
    this.defaultOptions = defaultOptions;
  }

  public void applyOptionsMap(Map<String, ActivityOptions> optionsMap) {
    if (optionsMap != null) {
      this.optionsMap.putAll(optionsMap);
    }
  }

  /** Get merged options for the given activityType. */
  public ActivityOptions getMergedOptions(String activityType) {
    ActivityOptions overrideOptions = null;
    if (overridden != null) {
      overrideOptions = overridden.getMergedOptions(activityType);
    }
    return merge(overrideOptions, defaultOptions, optionsMap.get(activityType));
  }

  /** later options override the previous ones */
  private static ActivityOptions merge(ActivityOptions... options) {
    if (options == null || options.length == 0) {
      return null;
    }
    ActivityOptions result = options[0];
    for (int i = 1; i < options.length; i++) {
      if (result == null) {
        result = options[i];
      } else {
        result = result.toBuilder().mergeActivityOptions(options[i]).build();
      }
    }
    return result;
  }
}
