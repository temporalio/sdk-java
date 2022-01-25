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

package io.temporal.internal.common;

import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import java.util.Map;
import javax.annotation.Nonnull;

public class ActivityOptionUtils {
  public static void mergePredefinedActivityOptions(
      @Nonnull Map<String, ActivityOptions> mergeTo,
      @Nonnull Map<String, ActivityOptions> override) {
    override.forEach(
        (key, value) ->
            mergeTo.merge(key, value, (o1, o2) -> o1.toBuilder().mergeActivityOptions(o2).build()));
  }

  public static void mergePredefinedLocalActivityOptions(
      @Nonnull Map<String, LocalActivityOptions> mergeTo,
      @Nonnull Map<String, LocalActivityOptions> override) {
    override.forEach(
        (key, value) ->
            mergeTo.merge(key, value, (o1, o2) -> o1.toBuilder().mergeActivityOptions(o2).build()));
  }
}
