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

package com.uber.cadence.workflow;

import java.lang.reflect.Type;

/**
 * ActivityStub is used to call an activity without referencing an interface it implements. This is
 * useful to call activities when their type is not known at compile time or to execute activities
 * implemented in other languages. Created through {@link Workflow#newActivityStub(Class)}.
 */
public interface ActivityStub {

  /**
   * Executes an activity by its type name and arguments. Blocks until the activity completion.
   *
   * @param activityName name of an activity type to execute.
   * @param resultClass the expected return type of the activity. Use Void.class for activities that
   *     return void type.
   * @param args arguments of the activity.
   * @param <R> return type.
   * @return an activity result.
   */
  <R> R execute(String activityName, Class<R> resultClass, Object... args);

  /**
   * Executes an activity by its type name and arguments. Blocks until the activity completion.
   *
   * @param activityName name of an activity type to execute.
   * @param resultClass the expected return class of the activity. Use Void.class for activities
   *     that return void type.
   * @param resultType the expected return type of the activity. Differs from resultClass for
   *     generic types.
   * @param args arguments of the activity.
   * @param <R> return type.
   * @return an activity result.
   */
  <R> R execute(String activityName, Class<R> resultClass, Type resultType, Object... args);

  /**
   * Executes an activity asynchronously by its type name and arguments.
   *
   * @param activityName name of an activity type to execute.
   * @param resultClass the expected return type of the activity. Use Void.class for activities that
   *     return void type.
   * @param args arguments of the activity.
   * @param <R> return type.
   * @return Promise to the activity result.
   */
  <R> Promise<R> executeAsync(String activityName, Class<R> resultClass, Object... args);

  /**
   * Executes an activity asynchronously by its type name and arguments.
   *
   * @param activityName name of an activity type to execute.
   * @param resultClass the expected return class of the activity. Use Void.class for activities
   *     that return void type.
   * @param resultType the expected return type of the activity. Differs from resultClass for
   *     generic types.
   * @param args arguments of the activity.
   * @param <R> return type.
   * @return Promise to the activity result.
   */
  <R> Promise<R> executeAsync(
      String activityName, Class<R> resultClass, Type resultType, Object... args);
}
