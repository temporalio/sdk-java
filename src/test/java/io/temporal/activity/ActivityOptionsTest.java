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

package io.temporal.activity;

import io.temporal.common.MethodRetry;
import io.temporal.common.RetryOptions;
import java.lang.reflect.Method;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Test;

public class ActivityOptionsTest {

  @MethodRetry(
      initialIntervalSeconds = 12,
      backoffCoefficient = 1.97,
      maximumAttempts = 234567,
      maximumIntervalSeconds = 22,
      doNotRetry = {"java.lang.NullPointerException", "java.lang.UnsupportedOperationException"})
  public void activityAndRetryOptions() {}

  @Test
  public void testOnlyAnnotationsPresent() throws NoSuchMethodException {
    Method method = ActivityOptionsTest.class.getMethod("activityAndRetryOptions");
    ActivityMethod a = method.getAnnotation(ActivityMethod.class);
    MethodRetry r = method.getAnnotation(MethodRetry.class);
    ActivityOptions o = ActivityOptions.newBuilder().build();
    ActivityOptions merged = ActivityOptions.newBuilder(o).mergeMethodRetry(r).build();

    RetryOptions rMerged = merged.getRetryOptions();
    Assert.assertEquals(r.maximumAttempts(), rMerged.getMaximumAttempts());
    Assert.assertEquals(r.backoffCoefficient(), rMerged.getBackoffCoefficient(), 0.0);
    Assert.assertEquals(
        Duration.ofSeconds(r.initialIntervalSeconds()), rMerged.getInitialInterval());
    Assert.assertEquals(
        Duration.ofSeconds(r.maximumIntervalSeconds()), rMerged.getMaximumInterval());
    Assert.assertArrayEquals(r.doNotRetry(), rMerged.getDoNotRetry());
  }
}
