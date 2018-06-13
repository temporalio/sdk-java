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

package com.uber.cadence.testing;

import com.google.common.annotations.VisibleForTesting;
import com.uber.cadence.internal.sync.TestActivityEnvironmentInternal;
import com.uber.cadence.serviceclient.IWorkflowService;
import java.lang.reflect.Type;
import java.util.function.Consumer;

/**
 * The helper class for unit testing activity implementations. Supports calls to {@link
 * com.uber.cadence.activity.Activity} methods from the tested activities. An example test:
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
    return newInstance(new TestEnvironmentOptions.Builder().build());
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
   * com.uber.cadence.activity.Activity#heartbeat(Object)}.
   *
   * @param detailsClass class of the details passed to the {@link
   *     com.uber.cadence.activity.Activity#heartbeat(Object)}.
   * @param listener listener to register.
   * @param <T> Type of the heartbeat details.
   */
  <T> void setActivityHeartbeatListener(Class<T> detailsClass, Consumer<T> listener);

  /**
   * Sets a listener that is called every time an activity implementation heartbeats through {@link
   * com.uber.cadence.activity.Activity#heartbeat(Object)}.
   *
   * @param detailsClass class of the details passed to the {@link
   *     com.uber.cadence.activity.Activity#heartbeat(Object)}.
   * @param detailsType type of the details. Differs for detailsClass for generic types.
   * @param listener listener to register.
   * @param <T> Type of the heartbeat details.
   */
  <T> void setActivityHeartbeatListener(
      Class<T> detailsClass, Type detailsType, Consumer<T> listener);

  void setWorkflowService(IWorkflowService workflowService);
}
