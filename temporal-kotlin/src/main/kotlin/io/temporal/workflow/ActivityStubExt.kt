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

package io.temporal.workflow

import kotlin.reflect.javaType
import kotlin.reflect.typeOf

/**
 * Executes an activity by its type name and arguments. Blocks until the activity completion.
 *
 * @param activityName name of an activity type to execute.
 * @param T the expected return class of the activity. Use `Void` for activities that return void
 * type.
 * @see ActivityStub.execute
 */
@OptIn(ExperimentalStdlibApi::class)
inline fun <reified T> ActivityStub.execute(activityName: String, vararg args: Any?): T {
  return execute(activityName, T::class.java, typeOf<T>().javaType, args)
}

/**
 * Executes an activity asynchronously by its type name and arguments.
 *
 * @param activityName name of an activity type to execute.
 * @param T the expected return class of the activity. Use `Void` for activities that return void
 * type.
 * @see ActivityStub.executeAsync
 */
@OptIn(ExperimentalStdlibApi::class)
inline fun <reified T> ActivityStub.executeAsync(
  activityName: String,
  vararg args: Any?
): Promise<T> {
  return executeAsync(activityName, T::class.java, typeOf<T>().javaType, args)
}
