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
 * @see ChildWorkflowStub.execute
 */
@OptIn(ExperimentalStdlibApi::class)
inline fun <reified T> ChildWorkflowStub.execute(vararg args: Any?): T {
  return execute(T::class.java, typeOf<T>().javaType, args)
}

/**
 * @see ChildWorkflowStub.executeAsync
 */
@OptIn(ExperimentalStdlibApi::class)
inline fun <reified T> ChildWorkflowStub.executeAsync(vararg args: Any?): Promise<T> {
  return executeAsync(T::class.java, typeOf<T>().javaType, args)
}
