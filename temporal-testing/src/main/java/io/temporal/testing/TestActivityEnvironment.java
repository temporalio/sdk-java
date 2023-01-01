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

package io.temporal.testing;

import com.google.common.annotations.VisibleForTesting;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.worker.TypeAlreadyRegisteredException;
import io.temporal.workflow.Functions;
import java.lang.reflect.Type;
import java.util.Map;

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
 *       return Activity.getExecutionContext().getInfo().getActivityType() + "-" + input;
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
   *
   * <p>Implementations that share a worker must implement different interfaces as an activity type
   * is identified by the activity interface, not by the implementation.
   *
   * @throws TypeAlreadyRegisteredException if one of the activity types is already registered
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
   * Creates a stub that can be used to invoke activities registered through {@link
   * #registerActivitiesImplementations(Object...)}.
   *
   * @param <T> Type of the activity interface.
   * @param activityInterface activity interface class that the object under test implements
   * @param options options that specify the activity invocation parameters
   * @return The stub that implements the activity interface.
   */
  <T> T newActivityStub(Class<T> activityInterface, ActivityOptions options);

  /**
   * Creates a stub that can be used to invoke activities registered through {@link
   * #registerActivitiesImplementations(Object...)}.
   *
   * @param <T> Type of the activity interface.
   * @param activityInterface activity interface class that the object under test implements
   * @param options options that specify the activity invocation parameters
   * @param activityMethodOptions a map keyed on Activity Type Name to its specific invocation
   *     parameters. By default the name of an activity type is its method name with the first
   *     letter capitalized.
   * @return The stub that implements the activity interface.
   */
  <T> T newLocalActivityStub(
      Class<T> activityInterface,
      LocalActivityOptions options,
      Map<String, LocalActivityOptions> activityMethodOptions);

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
   * Sets heartbeat details for the next activity execution. The next activity
   * called from this TestActivityEnvironment will be able to access this value
   * using {@link io.temporal.activity.ActivityExecutionContext#getHeartbeatDetails(Class)}.
   * This value is cleared upon execution.
   * @param details The details object to make available to the next activity call.
   * @param <T> Type of the heartbeat details.
   */
  <T> void setHeartbeatDetails(T details);

  /**
   * Requests activity cancellation. The cancellation is going to be delivered to the activity on
   * the next heartbeat.
   */
  void requestCancelActivity();

  void close();
}
