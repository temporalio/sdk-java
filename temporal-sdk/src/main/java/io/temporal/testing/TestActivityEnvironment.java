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

package io.temporal.testing;

import com.google.common.annotations.VisibleForTesting;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.internal.sync.TestActivityEnvironmentInternal;
import io.temporal.workflow.Functions;
import java.lang.reflect.Type;

/**
 * The helper class for unit testing activity implementations. Supports calls to {@link
 * io.temporal.activity.Activity} methods from the tested activities. An example test:
 *
 * <pre><code>
 *   public interface TestActivity {
 *     String activity1(String input);
 *   }
 *
 *   private static class ActivityImpl implements TestActivity {
 *    {@literal @}Override
 *     public String activity1(String input) {
 *       return Activity.getTask().getActivityType().getName() + "-" + input;
 *     }
 *   }
 *
 *  {@literal @}Test
 *   public void testSuccess() {
 *     testEnvironment.registerActivitiesImplementations(new ActivityImpl());
 *     TestActivity activity = testEnvironment.newActivityStub(TestActivity.class);
 *     String result = activity.activity1("input1");
 *     assertEquals("TestActivity::activity1-input1", result);
 *   }
 * </code></pre>
 *
 * Use {@link TestWorkflowEnvironment} to test a workflow code.
 */
@VisibleForTesting
public interface TestActivityEnvironment {

  static TestActivityEnvironment newInstance() {
    return newInstance(TestEnvironmentOptions.getDefaultInstance());
  }

  static TestActivityEnvironment newInstance(TestEnvironmentOptions options) {
    return new TestActivityEnvironmentInternal(options);
  }

  /**
   * Registers activity implementations to test. Use {@link #newActivityStub(Class)} to create stubs
   * that can be used to invoke them.
   */
  void registerActivitiesImplementations(Object... activityImplementations);

  /**
   * Creates a stub that can be used to invoke activities registered through {@link
   * #registerActivitiesImplementations(Object...)}.
   *
   * @param activityInterface activity interface class that the object under test implements.
   * @param <T> Type of the activity interface.
   * @return The stub that implements the activity interface.
   */
  <T> T newActivityStub(Class<T> activityInterface);

  /**
   * Sets a listener that is called every time an activity implementation heartbeats through {@link
   * ActivityExecutionContext#heartbeat(Object)}.
   *
   * @param detailsClass class of the details passed to the {@link
   *     ActivityExecutionContext#heartbeat(Object)}.
   * @param listener listener to register.
   * @param <T> Type of the heartbeat details.
   */
  <T> void setActivityHeartbeatListener(Class<T> detailsClass, Functions.Proc1<T> listener);

  /**
   * Sets a listener that is called every time an activity implementation heartbeats through {@link
   * io.temporal.activity.ActivityExecutionContext#heartbeat(Object)}.
   *
   * @param detailsClass class of the details passed to the {@link
   *     io.temporal.activity.ActivityExecutionContext#heartbeat(Object)}.
   * @param detailsType type of the details. Differs for detailsClass for generic types.
   * @param listener listener to register.
   * @param <T> Type of the heartbeat details.
   */
  <T> void setActivityHeartbeatListener(
      Class<T> detailsClass, Type detailsType, Functions.Proc1<T> listener);

  /**
   * Requests activity cancellation. The cancellation is going to be delivered to the activity on
   * the next heartbeat.
   */
  void requestCancelActivity();

  void close();
}
