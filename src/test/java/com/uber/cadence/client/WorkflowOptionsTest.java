/*
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

package com.uber.cadence.client;

import com.uber.cadence.WorkflowIdReusePolicy;
import com.uber.cadence.common.CronSchedule;
import com.uber.cadence.common.MethodRetry;
import com.uber.cadence.common.RetryOptions;
import com.uber.cadence.workflow.ChildWorkflowOptions;
import com.uber.cadence.workflow.WorkflowMethod;
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
        new WorkflowOptions.Builder()
            .setTaskList("foo")
            .setExecutionStartToCloseTimeout(Duration.ofSeconds(321))
            .setTaskStartToCloseTimeout(Duration.ofSeconds(13))
            .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.RejectDuplicate)
            .setMemo(getTestMemo())
            .setSearchAttributes(getTestSearchAttributes())
            .build();
    WorkflowMethod a =
        WorkflowOptionsTest.class
            .getMethod("defaultWorkflowOptions")
            .getAnnotation(WorkflowMethod.class);
    Assert.assertEquals(o, WorkflowOptions.merge(a, null, null, o));
  }

  @WorkflowMethod(
    executionStartToCloseTimeoutSeconds = 1135,
    taskList = "bar",
    taskStartToCloseTimeoutSeconds = 34,
    workflowId = "foo",
    workflowIdReusePolicy = WorkflowIdReusePolicy.AllowDuplicate
  )
  @MethodRetry(
    initialIntervalSeconds = 12,
    backoffCoefficient = 1.97,
    expirationSeconds = 1231423,
    maximumAttempts = 234567,
    maximumIntervalSeconds = 22,
    doNotRetry = {NullPointerException.class, UnsupportedOperationException.class}
  )
  @CronSchedule("0 * * * *" /* hourly */)
  public void workflowOptions() {}

  @Test
  public void testOnlyAnnotationsPresent() throws NoSuchMethodException {
    Method method = WorkflowOptionsTest.class.getMethod("workflowOptions");
    WorkflowMethod a = method.getAnnotation(WorkflowMethod.class);
    MethodRetry r = method.getAnnotation(MethodRetry.class);
    CronSchedule c = method.getAnnotation(CronSchedule.class);
    WorkflowOptions o = new WorkflowOptions.Builder().build();
    WorkflowOptions merged = WorkflowOptions.merge(a, r, c, o);
    Assert.assertEquals(a.taskList(), merged.getTaskList());
    Assert.assertEquals(
        a.executionStartToCloseTimeoutSeconds(),
        merged.getExecutionStartToCloseTimeout().getSeconds());
    Assert.assertEquals(
        a.taskStartToCloseTimeoutSeconds(), merged.getTaskStartToCloseTimeout().getSeconds());
    Assert.assertEquals(a.workflowId(), merged.getWorkflowId());
    Assert.assertEquals(a.workflowIdReusePolicy(), merged.getWorkflowIdReusePolicy());
    Assert.assertEquals("0 * * * *", merged.getCronSchedule());
  }

  @Test
  public void testBothPresent() throws NoSuchMethodException {
    RetryOptions retryOptions =
        new RetryOptions.Builder()
            .setDoNotRetry(IllegalArgumentException.class)
            .setMaximumAttempts(11111)
            .setBackoffCoefficient(1.55)
            .setMaximumInterval(Duration.ofDays(3))
            .setExpiration(Duration.ofDays(365))
            .setInitialInterval(Duration.ofMinutes(12))
            .build();

    Map<String, Object> memo = getTestMemo();
    Map<String, Object> searchAttributes = getTestSearchAttributes();

    WorkflowOptions o =
        new WorkflowOptions.Builder()
            .setTaskList("foo")
            .setExecutionStartToCloseTimeout(Duration.ofSeconds(321))
            .setTaskStartToCloseTimeout(Duration.ofSeconds(13))
            .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.RejectDuplicate)
            .setWorkflowId("bar")
            .setRetryOptions(retryOptions)
            .setCronSchedule("* 1 * * *")
            .setMemo(memo)
            .setSearchAttributes(searchAttributes)
            .build();
    Method method = WorkflowOptionsTest.class.getMethod("workflowOptions");
    WorkflowMethod a = method.getAnnotation(WorkflowMethod.class);
    MethodRetry r = method.getAnnotation(MethodRetry.class);
    CronSchedule c = method.getAnnotation(CronSchedule.class);
    WorkflowOptions merged = WorkflowOptions.merge(a, r, c, o);
    Assert.assertEquals(retryOptions, merged.getRetryOptions());
    Assert.assertEquals("* 1 * * *", merged.getCronSchedule());
    Assert.assertEquals(memo, merged.getMemo());
    Assert.assertEquals(searchAttributes, merged.getSearchAttributes());
  }

  @Test
  public void testChildWorkflowOptionMerge() throws NoSuchMethodException {
    RetryOptions retryOptions =
        new RetryOptions.Builder()
            .setDoNotRetry(IllegalArgumentException.class)
            .setMaximumAttempts(11111)
            .setBackoffCoefficient(1.55)
            .setMaximumInterval(Duration.ofDays(3))
            .setExpiration(Duration.ofDays(365))
            .setInitialInterval(Duration.ofMinutes(12))
            .build();

    Map<String, Object> memo = getTestMemo();
    Map<String, Object> searchAttributes = getTestSearchAttributes();
    ChildWorkflowOptions o =
        new ChildWorkflowOptions.Builder()
            .setTaskList("foo")
            .setExecutionStartToCloseTimeout(Duration.ofSeconds(321))
            .setTaskStartToCloseTimeout(Duration.ofSeconds(13))
            .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.RejectDuplicate)
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
    ChildWorkflowOptions merged = ChildWorkflowOptions.merge(a, r, c, o);
    Assert.assertEquals(retryOptions, merged.getRetryOptions());
    Assert.assertEquals("* 1 * * *", merged.getCronSchedule());
    Assert.assertEquals(memo, merged.getMemo());
    Assert.assertEquals(searchAttributes, merged.getSearchAttributes());
  }

  @WorkflowMethod
  @CronSchedule("invalid * * * *")
  public void invalidCronScheduleAnnotation() {}

  @Test
  public void testInvalidCronScheduleAnnotation() throws NoSuchMethodException {
    WorkflowOptions o =
        new WorkflowOptions.Builder()
            .setTaskList("foo")
            .setExecutionStartToCloseTimeout(Duration.ofSeconds(321))
            .setTaskStartToCloseTimeout(Duration.ofSeconds(13))
            .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.RejectDuplicate)
            .build();
    Method method = WorkflowOptionsTest.class.getMethod("invalidCronScheduleAnnotation");
    WorkflowMethod a = method.getAnnotation(WorkflowMethod.class);
    CronSchedule c = method.getAnnotation(CronSchedule.class);

    try {
      WorkflowOptions.merge(a, null, c, o);
    } catch (IllegalArgumentException e) {
      return;
    }

    Assert.fail("invalid cron schedule not caught");
  }

  private Map<String, Object> getTestMemo() {
    Map<String, Object> memo = new HashMap<>();
    memo.put("testKey", "testObject");
    memo.put("objectKey", new WorkflowOptions.Builder().build());
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
