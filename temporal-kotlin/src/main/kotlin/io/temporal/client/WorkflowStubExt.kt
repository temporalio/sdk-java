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

package io.temporal.client

import java.util.concurrent.CompletableFuture
import kotlin.reflect.javaType
import kotlin.reflect.typeOf

/**
 * Returns workflow result potentially waiting for workflow to complete. Behind the scene this
 * call performs long poll on Temporal service waiting for workflow completion notification.
 *
 * @param T type of the workflow return value
 * @return workflow return value
 */
@OptIn(ExperimentalStdlibApi::class)
inline fun <reified T> WorkflowStub.getResult(): T {
  return getResult(T::class.java, typeOf<T>().javaType)
}

@OptIn(ExperimentalStdlibApi::class)
inline fun <reified T> WorkflowStub.getResultAsync(): CompletableFuture<T> {
  return getResultAsync(T::class.java, typeOf<T>().javaType)
}
