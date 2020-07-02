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

package io.temporal.client;

import io.temporal.common.CronSchedule;
import io.temporal.common.MethodRetry;
import io.temporal.common.RetryOptions;
import io.temporal.enums.v1.WorkflowIdReusePolicy;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.WorkflowMethod;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class WorkflowOptionsTest {

  @WorkflowMethod
  public void defaultWorkflowOptions() {}

  @Test
  public void testOnlyOptionsAndEmptyAnnotationsPresent() throws NoSuchMethodException {
    WorkflowOptions o =
        WorkflowOptions.newBuilder()
            .setTaskQueue("foo")
            .setWorkflowRunTimeout(Duration.ofSeconds(321))
            .setWorkflowExecutionTimeout(Duration.ofSeconds(456))
            .setWorkflowTaskTimeout(Duration.ofSeconds(13))
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
            .setMemo(getTestMemo())
            .setSearchAttributes(getTestSearchAttributes())
            .build();
    Assert.assertEquals(o, WorkflowOptions.merge(null, null, o));
  }

  @MethodRetry(
      initialIntervalSeconds = 12,
      backoffCoefficient = 1.97,
      maximumAttempts = 234567,
      maximumIntervalSeconds = 22,
      doNotRetry = {"java.lang.NullPointerException", "java.lang.UnsupportedOperationException"})
  @CronSchedule("0 * * * *" /* hourly */)
  public void workflowOptions() {}

  @Test
  public void testOnlyAnnotationsPresent() throws NoSuchMethodException {
    Method method = WorkflowOptionsTest.class.getMethod("workflowOptions");
    MethodRetry r = method.getAnnotation(MethodRetry.class);
    CronSchedule c = method.getAnnotation(CronSchedule.class);
    WorkflowOptions o = WorkflowOptions.newBuilder().build();
    WorkflowOptions merged = WorkflowOptions.merge(r, c, o);
    Assert.assertEquals("0 * * * *", merged.getCronSchedule());
  }

  @Test
  public void testBothPresent() throws NoSuchMethodException {
    RetryOptions retryOptions =
        RetryOptions.newBuilder()
            .setDoNotRetry(IllegalArgumentException.class.getName())
            .setMaximumAttempts(11111)
            .setBackoffCoefficient(1.55)
            .setMaximumInterval(Duration.ofDays(3))
            .setInitialInterval(Duration.ofMinutes(12))
            .build();

    Map<String, Object> memo = getTestMemo();
    Map<String, Object> searchAttributes = getTestSearchAttributes();

    WorkflowOptions o =
        WorkflowOptions.newBuilder()
            .setTaskQueue("foo")
            .setWorkflowRunTimeout(Duration.ofSeconds(321))
            .setWorkflowTaskTimeout(Duration.ofSeconds(13))
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
            .setWorkflowId("bar")
            .setRetryOptions(retryOptions)
            .setCronSchedule("* 1 * * *")
            .setMemo(memo)
            .setSearchAttributes(searchAttributes)
            .build();
    Method method = WorkflowOptionsTest.class.getMethod("workflowOptions");
    MethodRetry r = method.getAnnotation(MethodRetry.class);
    CronSchedule c = method.getAnnotation(CronSchedule.class);
    WorkflowOptions merged = WorkflowOptions.merge(r, c, o);
    Assert.assertEquals(retryOptions, merged.getRetryOptions());
    Assert.assertEquals("* 1 * * *", merged.getCronSchedule());
    Assert.assertEquals(memo, merged.getMemo());
    Assert.assertEquals(searchAttributes, merged.getSearchAttributes());
  }

  @Test
  public void testChildWorkflowOptionMerge() throws NoSuchMethodException {
    RetryOptions retryOptions =
        RetryOptions.newBuilder()
            .setDoNotRetry(IllegalArgumentException.class.getName())
            .setMaximumAttempts(11111)
            .setBackoffCoefficient(1.55)
            .setMaximumInterval(Duration.ofDays(3))
            .setInitialInterval(Duration.ofMinutes(12))
            .build();

    Map<String, Object> memo = getTestMemo();
    Map<String, Object> searchAttributes = getTestSearchAttributes();
    ChildWorkflowOptions o =
        ChildWorkflowOptions.newBuilder()
            .setTaskQueue("foo")
            .setWorkflowRunTimeout(Duration.ofSeconds(321))
            .setWorkflowTaskTimeout(Duration.ofSeconds(13))
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
            .setWorkflowId("bar")
            .setRetryOptions(retryOptions)
            .setCronSchedule("* 1 * * *")
            .setMemo(memo)
            .setSearchAttributes(searchAttributes)
            .build();
    Method method = WorkflowOptionsTest.class.getMethod("defaultWorkflowOptions");
    WorkflowMethod a = method.getAnnotation(WorkflowMethod.class);
    MethodRetry r = method.getAnnotation(MethodRetry.class);
    CronSchedule c = method.getAnnotation(CronSchedule.class);
    ChildWorkflowOptions merged =
        ChildWorkflowOptions.newBuilder(o)
            .setMethodRetry(r)
            .setCronSchedule(c)
            .validateAndBuildWithDefaults();
    Assert.assertEquals(retryOptions, merged.getRetryOptions());
    Assert.assertEquals("* 1 * * *", merged.getCronSchedule());
    Assert.assertEquals(memo, merged.getMemo());
    Assert.assertEquals(searchAttributes, merged.getSearchAttributes());
  }

  private Map<String, Object> getTestMemo() {
    Map<String, Object> memo = new HashMap<>();
    memo.put("testKey", "testObject");
    memo.put("objectKey", WorkflowOptions.newBuilder().build());
    return memo;
  }

  private Map<String, Object> getTestSearchAttributes() {
    Map<String, Object> searchAttr = new HashMap<>();
    searchAttr.put("CustomKeywordField", "testKey");
    searchAttr.put("CustomIntField", 1);
    searchAttr.put("CustomDoubleField", 1.23);
    searchAttr.put("CustomBoolField", false);
    searchAttr.put("CustomDatetimeField", LocalDateTime.now());
    return searchAttr;
  }
}
