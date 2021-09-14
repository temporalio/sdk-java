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

package io.temporal.common.metadata

import kotlin.reflect.KFunction
import kotlin.reflect.jvm.javaMethod

/**
 * Resolves activity name by the activity method reference.
 *
 * ```kotlin
 * val activityName = activityName(ActivityInterface::activityMethod)
 * ```
 */
fun activityName(method: KFunction<*>): String {
  val javaMethod = method.javaMethod
    ?: throw IllegalArgumentException("Invalid method reference $method")
  val interfaceMetadata = POJOActivityInterfaceMetadata.newInstance(javaMethod.declaringClass)
  val methodMetadata = interfaceMetadata.methodsMetadata.find { it.method == javaMethod }
    ?: throw IllegalArgumentException("Not an activity method reference $method")
  return methodMetadata.activityTypeName
}
