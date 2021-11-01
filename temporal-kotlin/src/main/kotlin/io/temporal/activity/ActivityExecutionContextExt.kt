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

package io.temporal.activity

import kotlin.reflect.javaType
import kotlin.reflect.typeOf

/**
 * Extracts Heartbeat details from the last failed attempt.
 *
 * @param T type of the Heartbeat details
 * @see ActivityExecutionContext.getHeartbeatDetails
 */
@OptIn(ExperimentalStdlibApi::class)
inline fun <reified T> ActivityExecutionContext.getHeartbeatDetailsOrNull(): T? {
  return getHeartbeatDetails(T::class.java, typeOf<T>().javaType).orElse(null)
}
